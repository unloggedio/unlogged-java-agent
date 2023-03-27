package com.videobug.agent.command;

public class AgentCommandErrorResponse {

    public String getMessage() {
        return message;
    }

    private final String message;

    public AgentCommandErrorResponse(String message) {
        this.message = message;
    }
}
