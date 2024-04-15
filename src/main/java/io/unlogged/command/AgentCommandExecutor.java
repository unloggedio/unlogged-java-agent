package io.unlogged.command;

public interface AgentCommandExecutor {
    AgentCommandRawResponse executeCommandRaw(AgentCommandRequest agentCommandRequest);

    AgentCommandResponse executeCommand(AgentCommandRequest agentCommandRequest) throws Exception;

    AgentCommandResponse injectMocks(AgentCommandRequest agentCommandRequest) throws Exception;

    AgentCommandResponse removeMocks(AgentCommandRequest agentCommandRequest) throws Exception;

    void setClassLoader(ClassLoader targetClassLoader);
}
