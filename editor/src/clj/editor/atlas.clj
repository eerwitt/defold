(ns editor.atlas
  (:require [clojure.set :refer [difference union]]
            [dynamo.background :as background]
            [dynamo.buffers :refer :all]
            [dynamo.camera :refer :all]
            [dynamo.file :as file]
            [dynamo.file.protobuf :as protobuf :refer [pb->str]]
            [dynamo.geom :as geom]
            [dynamo.gl :as gl]
            [dynamo.gl.shader :as shader]
            [dynamo.gl.texture :as texture]
            [dynamo.gl.vertex :as vtx]
            [dynamo.graph :as g]
            [dynamo.grid :as grid]
            [dynamo.image :refer :all]
            [dynamo.node :as n]
            [dynamo.project :as p]
            [dynamo.property :as dp]
            [dynamo.system :as ds]
            [dynamo.texture :as tex]
            [dynamo.types :as t :refer :all]
            [dynamo.ui :refer :all]
            [editor.camera :as c]
            [editor.image-node :as ein]
            [editor.scene :as scene]
            [internal.render.pass :as pass]
            [internal.transaction :as it]
            [schema.macros :as sm])
  (:import [com.dynamo.atlas.proto AtlasProto AtlasProto$Atlas AtlasProto$AtlasAnimation AtlasProto$AtlasImage]
           [com.dynamo.graphics.proto Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.dynamo.textureset.proto TextureSetProto$Constants TextureSetProto$TextureSet TextureSetProto$TextureSetAnimation]
           [com.dynamo.tile.proto Tile$Playback]
           [com.jogamp.opengl.util.awt TextRenderer]
           [dynamo.types Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [java.awt.image BufferedImage]
           [javax.media.opengl GL GL2 GLContext GLDrawableFactory]
           [javax.media.opengl.glu GLU]
           [javax.vecmath Matrix4d]))

(vtx/defvertex engine-format-texture
  (vec3.float position)
  (vec2.short texcoord0 true))

(vtx/defvertex texture-vtx
  (vec4 position)
  (vec2 texcoord0))

(vtx/defvertex uv-only
  (vec2 uv))

(declare tex-outline-vertices)

(defn- input-connections
  [graph node input-label]
  (mapv (fn [[source-node output-label]] [(:_id source-node) output-label])
    (ds/sources-of graph node input-label)))

(defn connect-outline-children
  [input-labels input-name transaction graph self label kind inputs-touched]
  (when (some (set input-labels) inputs-touched)
    (let [children-before (input-connections (ds/in-transaction-graph transaction) self input-name)
          children-after  (mapcat #(input-connections graph self %) input-labels)]
      (concat
       (for [[n l] children-before]
         (it/disconnect {:_id n} l self input-name))
       (for [[n _] children-after]
         (it/connect {:_id n} :outline-tree self input-name))))))

(g/defnode AnimationGroupNode
  (inherits g/OutlineNode)
  (output outline-label t/Str (g/fnk [id] id))

  (property images dp/ImageResourceList)
  (input images-outline-children [OutlineItem])
  (trigger connect-images-outline-children :input-connections (partial connect-outline-children [:images] :images-outline-children))
  (output outline-children [OutlineItem] (g/fnk [images-outline-children] images-outline-children))
  (property id t/Str)
  (property fps             dp/NonNegativeInt (default 30))
  (property flip-horizontal t/Bool)
  (property flip-vertical   t/Bool)
  (property playback        AnimationPlayback (default :PLAYBACK_ONCE_FORWARD))

  (input images [ein/ImageResourceNode])

  (output animation Animation
    (g/fnk [this id images :- [Image] fps flip-horizontal flip-vertical playback]
      (->Animation id images fps flip-horizontal flip-vertical playback))))

(g/defnk produce-texture-packing :- TexturePacking
  [this images :- [Image] animations :- [Animation] margin extrude-borders]
  (let [animations (concat animations (map tex/animation-from-image images))
        _          (assert (pos? (count animations)))
        images     (seq (into #{} (mapcat :images animations)))
        _          (assert (pos? (count images)))
        texture-packing (tex/pack-textures margin extrude-borders images)]
    (assoc texture-packing :animations animations)))

(defn summarize-frame-data [key vbuf-factory-fn frame-data]
  (let [counts (map #(count (get % key)) frame-data)
        starts (reductions + 0 counts)
        total  (last starts)
        starts (butlast starts)
        vbuf   (vbuf-factory-fn total)]
    (doseq [frame frame-data
            vtx (get frame key)]
      (conj! vbuf vtx))
    {:starts (map int starts) :counts (map int counts) :vbuf (persistent! vbuf)}))

(defn animation-frame-data
  [^TexturePacking texture-packing image]
  (let [coords (filter #(= (:path image) (:path %)) (:coords texture-packing))
        ; TODO: may fail due to #87253110 "Atlas texture should not contain multiple instances of the same image"
        ;_ (assert (= 1 (count coords)))
        ^Rect coord (first coords)
        packed-image ^BufferedImage (.packed-image texture-packing)
        x-scale (/ 1.0 (.getWidth  packed-image))
        y-scale (/ 1.0 (.getHeight packed-image))
        u0 (* x-scale (+ (.x coord)))
        v0 (* y-scale (+ (.y coord)))
        u1 (* x-scale (+ (.x coord) (.width  coord)))
        v1 (* y-scale (+ (.y coord) (.height coord)))
        x0 (* -0.5 (.width  coord))
        y0 (* -0.5 (.height coord))
        x1 (*  0.5 (.width  coord))
        y1 (*  0.5 (.height coord))
        outline-vertices [[x0 y0 0 (geom/to-short-uv u0) (geom/to-short-uv v1)]
                          [x1 y0 0 (geom/to-short-uv u1) (geom/to-short-uv v1)]
                          [x1 y1 0 (geom/to-short-uv u1) (geom/to-short-uv v0)]
                          [x0 y1 0 (geom/to-short-uv u0) (geom/to-short-uv v0)]]]
    {:image            image ; TODO: is this necessary?
     :outline-vertices outline-vertices
     :vertices         (mapv outline-vertices [0 1 2 0 2 3])
     :tex-coords       [[u0 v0] [u1 v1]]}))

(defn build-textureset-animation
  [animation]
  (let [images (:images animation)
        width  (int (:width  (first images)))
        height (int (:height (first images)))]
    (-> (select-keys animation [:id :fps :flip-horizontal :flip-vertical :playback])
        (assoc :width width :height height)
        t/map->TextureSetAnimation)))

(g/defnk produce-textureset :- TextureSet
  [this texture-packing :- TexturePacking]
  (let [animations             (sort-by :id (:animations texture-packing))
        animations             (remove #(empty? (:images %)) animations)
        animations-images      (for [a animations i (:images a)] [a i])
        images                 (mapcat :images animations)
        frame-data             (map (partial animation-frame-data texture-packing) images)
        vertex-summary         (summarize-frame-data :vertices         ->engine-format-texture frame-data)
        outline-vertex-summary (summarize-frame-data :outline-vertices ->engine-format-texture frame-data)
        tex-coord-summary      (summarize-frame-data :tex-coords       ->uv-only               frame-data)
        frames                 (map t/->TextureSetAnimationFrame
                                 images
                                 (:starts vertex-summary)
                                 (:counts vertex-summary)
                                 (:starts outline-vertex-summary)
                                 (:counts outline-vertex-summary)
                                 (:starts tex-coord-summary)
                                 (:counts tex-coord-summary))
        animation-frames       (partition-by first (map (fn [[a i] f] [a f]) animations-images frames))
        textureset-animations  (map build-textureset-animation animations)
        textureset-animations  (map (fn [a aframes] (assoc a :frames (mapv second aframes))) textureset-animations animation-frames)]
    (t/map->TextureSet {:animations       (reduce (fn [m a] (assoc m (:id a) a)) {} textureset-animations)
                        :vertices         (:vbuf vertex-summary)
                        :outline-vertices (:vbuf outline-vertex-summary)
                        :tex-coords       (:vbuf tex-coord-summary)})))

(sm/defn build-atlas-image :- AtlasProto$AtlasImage
  [image :- Image]
  (.build (doto (AtlasProto$AtlasImage/newBuilder)
            (.setImage (str "/" (:path image))))))

(sm/defn build-atlas-animation :- AtlasProto$AtlasAnimation
  [animation :- Animation]
  (.build (doto (AtlasProto$AtlasAnimation/newBuilder)
            (.addAllImages           (map build-atlas-image (.images animation)))
            (.setId                  (.id animation))
            (.setFps                 (.fps animation))
            (protobuf/set-if-present :flip-horizontal animation)
            (protobuf/set-if-present :flip-vertical animation)
            (protobuf/set-if-present :playback animation (partial protobuf/val->pb-enum Tile$Playback)))))

(g/defnk get-text-format :- t/Str
  "get the text string for this node"
  [this images :- [Image] animations :- [Animation]]
  (pb->str
    (.build
         (doto (AtlasProto$Atlas/newBuilder)
             (.addAllImages           (map build-atlas-image images))
             (.addAllAnimations       (map build-atlas-animation animations))
             (protobuf/set-if-present :margin this)
             (protobuf/set-if-present :extrude-borders this)))))

(g/defnk save-atlas-file
  [this filename text-format]
  (file/write-file filename (.getBytes text-format))
  :ok)

(defn render-overlay
  [ctx ^GL2 gl ^TextRenderer text-renderer texture-packing]
  (let [image ^BufferedImage (.packed-image texture-packing)]
    (gl/overlay ctx gl text-renderer (format "Size: %dx%d" (.getWidth image) (.getHeight image)) 12.0 -22.0 1.0 1.0)))

(shader/defshader pos-uv-vert
  (attribute vec4 position)
  (attribute vec2 texcoord0)
  (varying vec2 var_texcoord0)
  (defn void main []
    (setq gl_Position (* gl_ModelViewProjectionMatrix position))
    (setq var_texcoord0 texcoord0)))

(shader/defshader pos-uv-frag
  (varying vec2 var_texcoord0)
  (uniform sampler2D texture)
  (defn void main []
    (setq gl_FragColor (texture2D texture var_texcoord0.xy))))

(def atlas-shader (shader/make-shader pos-uv-vert pos-uv-frag))

(defn render-texture-packing
  [ctx gl texture-packing vertex-binding gpu-texture]
  (gl/with-enabled gl [gpu-texture atlas-shader vertex-binding]
    (shader/set-uniform atlas-shader gl "texture" 0)
    (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (* 6 (count (:coords texture-packing))))))

(defn render-quad
  [ctx gl texture-packing vertex-binding gpu-texture i]
  (gl/with-enabled gl [gpu-texture atlas-shader vertex-binding]
    (shader/set-uniform atlas-shader gl "texture" 0)
    (gl/gl-draw-arrays gl GL/GL_TRIANGLES (* 6 i) 6)))

(g/defnk produce-renderable :- RenderData
  [this texture-packing vertex-binding gpu-texture]
  {pass/overlay
   [{:world-transform geom/Identity4d
     :render-fn       (fn [ctx gl glu text-renderer] (render-overlay ctx gl text-renderer texture-packing))}]
   pass/transparent
   [{:world-transform geom/Identity4d
     :render-fn       (fn [ctx gl glu text-renderer] (render-texture-packing ctx gl texture-packing vertex-binding gpu-texture))}]})

(g/defnk produce-renderable-vertex-buffer
  [[:texture-packing aabb coords]]
  (let [vbuf       (->texture-vtx (* 6 (count coords)))
        x-scale    (/ 1.0 (.width aabb))
        y-scale    (/ 1.0 (.height aabb))]
    (doseq [coord coords]
      (let [w  (.width coord)
            h  (.height coord)
            x0 (.x coord)
            y0 (- (.height aabb) (.y coord)) ;; invert for screen
            x1 (+ x0 w)
            y1 (- (.height aabb) (+ (.y coord) h))
            u0 (* x0 x-scale)
            v0 (* y0 y-scale)
            u1 (* x1 x-scale)
            v1 (* y1 y-scale)]
        (doto vbuf
          (conj! [x0 y0 0 1 u0 (- 1 v0)])
          (conj! [x0 y1 0 1 u0 (- 1 v1)])
          (conj! [x1 y1 0 1 u1 (- 1 v1)])

          (conj! [x1 y1 0 1 u1 (- 1 v1)])
          (conj! [x1 y0 0 1 u1 (- 1 v0)])
          (conj! [x0 y0 0 1 u0 (- 1 v0)]))))
    (persistent! vbuf)))

(g/defnk produce-outline-vertex-buffer
  [[:texture-packing aabb coords]]
  (let [vbuf       (->texture-vtx (* 6 (count coords)))
        x-scale    (/ 1.0 (.width aabb))
        y-scale    (/ 1.0 (.height aabb))]
    (doseq [coord coords]
      (let [w  (.width coord)
            h  (.height coord)
            x0 (.x coord)
            y0 (- (.height aabb) (.y coord)) ;; invert for screen
            x1 (+ x0 w)
            y1 (- (.height aabb) (+ y0 h))
            u0 (* x0 x-scale)
            v0 (* y0 y-scale)
            u1 (* x1 x-scale)
            v1 (* y1 y-scale)]
        (doto vbuf
          (conj! [x0 y0 0 1 u0 (- 1 v0)])
          (conj! [x0 y1 0 1 u0 (- 1 v1)])
          (conj! [x1 y1 0 1 u1 (- 1 v1)])
          (conj! [x1 y0 0 1 u1 (- 1 v0)]))))
    (persistent! vbuf)))

(g/defnode AtlasRender
  (input gpu-texture t/Any)
  (input texture-packing t/Any)

  (output vertex-buffer t/Any         :cached produce-renderable-vertex-buffer)
  (output outline-vertex-buffer t/Any :cached produce-outline-vertex-buffer)
  (output vertex-binding t/Any        :cached (g/fnk [vertex-buffer] (vtx/use-with vertex-buffer atlas-shader)))
  (output renderable RenderData       produce-renderable))

(defn build-animation
  [anim begin]
  (let [start begin
        end   (+ begin (count (:frames anim)))]
    (.build
      (doto (TextureSetProto$TextureSetAnimation/newBuilder)
         (.setId                  (:id anim))
         (.setWidth               (int (:width  anim)))
         (.setHeight              (int (:height anim)))
         (.setStart               (int start))
         (.setEnd                 (int end))
         (protobuf/set-if-present :playback anim (partial protobuf/val->pb-enum Tile$Playback))
         (protobuf/set-if-present :fps anim)
         (protobuf/set-if-present :flip-horizontal anim)
         (protobuf/set-if-present :flip-vertical anim)
         (protobuf/set-if-present :is-animation anim)))))

(defn build-animations
  [start-idx animations]
  (let [frame-counts     (map #(count (:frames %)) animations)
        animation-starts (butlast (reductions + start-idx frame-counts))]
    (map build-animation animations animation-starts)))

(defn summarize-frames [key vbuf-factory-fn frames]
  (let [counts (map #(count (get % key)) frames)
        starts (reductions + 0 counts)
        total  (last starts)
        starts (butlast starts)
        vbuf   (vbuf-factory-fn total)]
    (doseq [frame frames
            vtx (get frame key)]
      (conj! vbuf vtx))
    {:starts (map int starts) :counts (map int counts) :vbuf (persistent! vbuf)}))

(defn texturesetc-protocol-buffer
  [texture-name textureset]
  (let [animations             (remove #(empty? (:frames %)) (:animations textureset))
        frames                 (mapcat :frames animations)
        vertex-summary         (summarize-frames :vertices         ->engine-format-texture frames)
        outline-vertex-summary (summarize-frames :outline-vertices ->engine-format-texture frames)
        tex-coord-summary      (summarize-frames :tex-coords       ->uv-only               frames)]
    (.build (doto (TextureSetProto$TextureSet/newBuilder)
            (.setTexture               texture-name)
            (.setTexCoords             (byte-pack (:vbuf tex-coord-summary)))
            (.addAllAnimations         (build-animations 0 animations))

            (.addAllVertexStart        (:starts vertex-summary))
            (.addAllVertexCount        (:counts vertex-summary))
            (.setVertices              (byte-pack (:vbuf vertex-summary)))

            (.addAllOutlineVertexStart (:starts outline-vertex-summary))
            (.addAllOutlineVertexCount (:counts outline-vertex-summary))
            (.setOutlineVertices       (byte-pack (:vbuf outline-vertex-summary)))

            (.setTileCount             (int 0))))))

(g/defnk compile-texturesetc :- t/Bool
  [this g project textureset :- TextureSet]
  (file/write-file (:textureset-filename this)
    (.toByteArray (texturesetc-protocol-buffer (:texture-name this) textureset)))
  :ok)

(defn- texturec-protocol-buffer
  [engine-format]
  (t/validate EngineFormatTexture engine-format)
  (.build (doto (Graphics$TextureImage/newBuilder)
            (.addAlternatives
              (doto (Graphics$TextureImage$Image/newBuilder)
                (.setWidth           (.width engine-format))
                (.setHeight          (.height engine-format))
                (.setOriginalWidth   (.original-width engine-format))
                (.setOriginalHeight  (.original-height engine-format))
                (.setFormat          (.format engine-format))
                (.setData            (byte-pack (.data engine-format)))
                (.addAllMipMapOffset (.mipmap-offsets engine-format))
                (.addAllMipMapSize   (.mipmap-sizes engine-format))))
            (.setType            (Graphics$TextureImage$Type/TYPE_2D))
            (.setCount           1))))

(g/defnk compile-texturec :- t/Bool
  [this g project packed-image :- BufferedImage]
  (file/write-file (:texture-filename this)
    (.toByteArray (texturec-protocol-buffer (tex/->engine-format packed-image))))
  :ok)

(g/defnode TextureSave
  (input textureset   TextureSet)
  (input packed-image BufferedImage)

  (property texture-filename    t/Str (default ""))
  (property texture-name        t/Str)
  (property textureset-filename t/Str (default ""))

  (output   texturec    t/Any compile-texturec)
  (output   texturesetc t/Any compile-texturesetc))

(defn broadcast-event [this event]
  (doseq [controller (first (g/node-value this :controllers))]
    (g/process-one-event controller event)))

(defn find-resource-nodes [project exts]
  (let [all-resource-nodes (filter (fn [node] (let [filename (:filename node)]
                                                (and filename (contains? exts (t/extension filename))))) (map first (ds/sources-of project :nodes)))
        filenames (map (fn [node]
                         (let [filename (:filename node)]
                           (str "/" (t/local-path filename)))) all-resource-nodes)]
    (zipmap filenames all-resource-nodes)))

(g/defnk build-atlas-outline-children
  [images-outline-children animations-outline-children]
  (concat images-outline-children animations-outline-children))

(g/defnode AtlasNode
  "This node represents an actual Atlas. It accepts a collection
   of images and animations. It emits a packed texture-packing.

   Inputs:
   images `[dynamo.types/Image]` - A collection of images that will be packed into the atlas.
   animations `[dynamo.types/Animation]` - A collection of animations that will be packed into the atlas.

   Properties:
   margin - Integer, must be zero or greater. The number of pixels of transparent space to leave between textures.
   extrude-borders - Integer, must be zero or greater. The number of pixels for which the outer edge of each texture will be duplicated.

   The margin fits outside the extruded border.

   Outputs
   aabb `dynamo.types.AABB` - The AABB of the packed texture, in pixel space.
   gpu-texture `Texture` - A wrapper for the BufferedImage with the actual pixels. Conforms to the right protocols so you can directly use this in rendering.
   text-format `String` - A saveable representation of the atlas, its animations, and images. Built as a text-formatted protocol buffer.
   texture-packing `dynamo.types/TexturePacking` - A data structure with full access to the original image bounds, their coordinates in the packed image, the BufferedImage, and outline coordinates.
   packed-image `BufferedImage` - BufferedImage instance with the actual pixels.
   textureset `dynamo.types/TextureSet` - A data structure that logically mirrors the texturesetc protocol buffer format."
  (inherits g/OutlineNode)
  (output outline-label t/Str (g/fnk [] "Atlas"))
  (inherits g/ResourceNode)
  (inherits g/Saveable)

  (property images dp/ImageResourceList (visible false))

  (property margin          dp/NonNegativeInt (default 0) (visible true))
  (property extrude-borders dp/NonNegativeInt (default 0) (visible true))
  (property filename (t/protocol PathManipulation) (visible false))

  (input animations [Animation])
  (input animations-outline-children [OutlineItem])
  (trigger connect-animations-outline-children :input-connections (partial connect-outline-children [:animations] :animations-outline-children))

  (input images [ein/ImageResourceNode])
  (input images-outline-children [OutlineItem])
  (trigger connect-images-outline-children :input-connections (partial connect-outline-children [:images] :images-outline-children))

  (output outline-children [OutlineItem] build-atlas-outline-children)

  (output aabb            AABB               (g/fnk [texture-packing] (geom/rect->aabb (:aabb texture-packing))))
  (output gpu-texture     t/Any      :cached (g/fnk [packed-image] (texture/image-texture packed-image)))
  (output save            t/Keyword          save-atlas-file)
  (output text-format     t/Str              get-text-format)
  (output texture-packing TexturePacking :cached :substitute-value (tex/blank-texture-packing) produce-texture-packing)
  (output packed-image    BufferedImage  :cached (g/fnk [texture-packing] (:packed-image texture-packing)))
  (output textureset      TextureSet     :cached produce-textureset)

  (on :load
      (let [project (:project event)
            input (:filename self)
            atlas (protobuf/pb->map (protobuf/read-text AtlasProto$Atlas input))
            img-nodes (find-resource-nodes project #{"png" "jpg"})]
        (g/set-property self :margin (:margin atlas))
        (g/set-property self :extrude-borders (:extrude-borders atlas))
        (doseq [anim (:animations atlas)
                :let [anim-node (g/add (apply n/construct AnimationGroupNode (mapcat identity (select-keys anim [:flip-horizontal :flip-vertical :fps :playback :id]))))
                      images (mapv :image (:images anim))]]
          (g/set-property anim-node :images images)
          (g/connect anim-node :animation self :animations)
          (doseq [image images]
            (when-let [img-node (get img-nodes image)]
              (g/connect img-node :content anim-node :images))))
        (let [images (mapv :image (:images atlas))]
          (g/set-property self :images images)
          (doseq [image images]
            (when-let [img-node (get img-nodes image)]
              (g/connect img-node :content self :images))))
        self))

  (on :unload
      (doseq [[animation-group _] (ds/sources-of self :animations)]
        (g/delete animation-group))))

(defn construct-atlas-editor
  [project-node atlas-node]
  (let [view (n/construct scene/SceneView)]
    (ds/in (g/add view)
           (let [atlas-render (g/add (n/construct AtlasRender))
                 renderer     (g/add (n/construct scene/SceneRenderer))
                 background   (g/add (n/construct background/Gradient))
                 grid         (g/add (n/construct grid/Grid))
                 camera       (g/add (n/construct c/CameraController :camera (c/make-camera :orthographic) :reframe true))]
             (g/connect background   :renderable      renderer     :renderables)
             (g/connect grid         :renderable      renderer     :renderables)
             (g/connect camera       :camera          grid         :camera)
             (g/connect camera       :camera          renderer     :camera)
             (g/connect camera       :input-handler   view         :input-handlers)
             (g/connect view         :viewport        camera       :viewport)
             (g/connect view         :viewport        renderer     :viewport)
             (g/connect renderer     :frame           view         :frame)

             (g/connect atlas-node   :texture-packing atlas-render :texture-packing)
             (g/connect atlas-node   :gpu-texture     atlas-render :gpu-texture)
             (g/connect atlas-render :renderable      renderer     :renderables)
             (g/connect atlas-node   :aabb            camera       :aabb)
             )
           view)))
