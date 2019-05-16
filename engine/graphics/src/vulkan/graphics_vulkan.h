#ifndef GRAPHICS_DEVICE_VULKAN
#define GRAPHICS_DEVICE_VULKAN

namespace dmGraphics
{
    struct Texture
    {
    	uint16_t      m_MipMapCount;
        uint32_t      m_Width;
        uint32_t      m_Height;
        uint32_t      m_OriginalWidth;
        uint32_t      m_OriginalHeight;
        TextureType   m_Type;
        void*         m_Texture;
        TextureParams m_Params;
    };

    const static uint32_t MAX_VERTEX_STREAM_COUNT = 8;
    const static uint32_t MAX_REGISTER_COUNT = 16;
    const static uint32_t MAX_TEXTURE_COUNT = 32;

    struct FrameBuffer
    {
        void*       m_ColorBuffer;
        void*       m_DepthBuffer;
        void*       m_StencilBuffer;
        uint32_t    m_ColorBufferSize;
        uint32_t    m_DepthBufferSize;
        uint32_t    m_StencilBufferSize;
    };

    struct VertexDeclaration
    {
        struct Stream
        {
            const char* m_Name;
            uint16_t    m_LogicalIndex;
            uint16_t    m_DescriptorIndex;
            uint16_t    m_Size;
            uint16_t    m_Offset;
            Type        m_Type;
            // bool        m_Normalize; // Not sure how to deal in VK
        };

        Stream      m_Streams[MAX_VERTEX_STREAM_COUNT];
        uint16_t    m_StreamCount;
        uint16_t    m_Stride;
    };

    struct RenderTarget
    {
        TextureParams   m_BufferTextureParams[MAX_BUFFER_TYPE_COUNT];
        HTexture        m_ColorBufferTexture;
        FrameBuffer     m_FrameBuffer;
    };

    struct Context
    {
        Context(const ContextParams& params);

        Vectormath::Aos::Vector4    m_ProgramRegisters[MAX_REGISTER_COUNT];
        FrameBuffer*                m_CurrentFrameBuffer;
        void*                       m_CurrentProgram;
        void*                       m_CurrentVertexBuffer;
        void*                       m_CurrentIndexBuffer;
        void*                       m_CurrentVertexDeclaration;
        WindowResizeCallback        m_WindowResizeCallback;
        void*                       m_WindowResizeCallbackUserData;
        WindowCloseCallback         m_WindowCloseCallback;
        void*                       m_WindowCloseCallbackUserData;
        WindowFocusCallback         m_WindowFocusCallback;
        void*                       m_WindowFocusCallbackUserData;
        Type                        m_CurrentIndexBufferType;
        TextureFilter               m_DefaultTextureMinFilter;
        TextureFilter               m_DefaultTextureMagFilter;
        CompareFunc                 m_DepthFunc;
        CompareFunc                 m_StencilFunc;
        StencilOp                   m_StencilOpSFail;
        StencilOp                   m_StencilOpDPFail;
        StencilOp                   m_StencilOpDPPass;
        uint32_t                    m_Width;
        uint32_t                    m_Height;
        uint32_t                    m_WindowWidth;
        uint32_t                    m_WindowHeight;
        uint32_t                    m_Dpi;
        int32_t                     m_ScissorRect[4];
        uint32_t                    m_StencilMask;
        uint32_t                    m_StencilFuncRef;
        uint32_t                    m_StencilFuncMask;
        uint32_t                    m_TextureFormatSupport;
        uint32_t                    m_WindowOpened : 1;
        uint32_t                    m_RedMask : 1;
        uint32_t                    m_GreenMask : 1;
        uint32_t                    m_BlueMask : 1;
        uint32_t                    m_AlphaMask : 1;
        uint32_t                    m_DepthMask : 1;
        // Only use for testing
        uint32_t                    m_RequestWindowClose : 1;
    };
}

#endif // __GRAPHICS_DEVICE_VULKAN__