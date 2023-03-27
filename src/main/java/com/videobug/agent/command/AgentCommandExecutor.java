package com.videobug.agent.command;

public interface AgentCommandExecutor {
    AgentCommandResponse executeCommand(AgentCommandRequest agentCommandRequest) throws Exception;
}
