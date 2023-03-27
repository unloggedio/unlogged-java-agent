package com.videobug.agent.command;

public class AgentCommandResponse {
    private Object methodReturnValue;

    public void setMethodReturnValue(Object methodReturnValue) {
        this.methodReturnValue = methodReturnValue;
    }

    public Object getMethodReturnValue() {
        return methodReturnValue;
    }
}
