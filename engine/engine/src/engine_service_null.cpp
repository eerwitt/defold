#include "engine_service.h"

dmEngineService::HEngineService dmEngineService::New(uint16_t)
{
	return 0;
}

void dmEngineService::Delete(dmEngineService::HEngineService)
{
}

void dmEngineService::Update(dmEngineService::HEngineService)
{
}

uint16_t dmEngineService::GetPort(dmEngineService::HEngineService)
{
	return 0;
}

uint16_t dmEngineService::GetServicePort(uint16_t default_port)
{
	return default_port;
}

void dmEngineService::InitProfiler(HEngineService engine_service, dmResource::HFactory factory, dmGameObject::HRegister regist)
{
}