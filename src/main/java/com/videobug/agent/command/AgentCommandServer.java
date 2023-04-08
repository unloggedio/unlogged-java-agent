package com.videobug.agent.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AgentCommandServer extends NanoHTTPD {

    ObjectMapper objectMapper = new ObjectMapper();
    private AgentCommandExecutor agentCommandExecutor;
    private String pingResponseBody;

    public AgentCommandServer(int port) {
        super(port);
        init();
    }

    public AgentCommandServer(String hostname, int port) {
        super(hostname, port);
        init();
    }

    public void init() {
        AgentCommandResponse pingResponse = new AgentCommandResponse();
        pingResponse.setMessage("ok");
        pingResponse.setResponseType(ResponseType.NORMAL);
        try {
            pingResponseBody = objectMapper.writeValueAsString(pingResponse);
        } catch (JsonProcessingException e) {
            // should never happen
        }

    }

    @Override
    public Response serve(IHTTPSession session) {
        String requestBodyText = null;
        Map<String, String> bodyParams = new HashMap<>();
        try {
            session.parseBody(bodyParams);
        } catch (IOException | ResponseException e) {
            return newFixedLengthResponse("{\"message\": \"" + e.getMessage() + "\", }");
        }
        requestBodyText = bodyParams.get("postData");
        String postBody = session.getQueryParameterString();

        String requestPath = session.getUri();
        Method requestMethod = session.getMethod();
//        System.err.println("[" + requestMethod + "] " + requestPath + ": " + postBody + " - " + requestBodyText);
        if (requestPath.equals("/ping")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", pingResponseBody);
        }
        try {
            AgentCommandRequest agentCommandRequest = objectMapper.readValue(
                    postBody != null ? postBody : requestBodyText,
                    AgentCommandRequest.class);
            AgentCommandResponse commandResponse;
            switch (agentCommandRequest.getCommand()) {
                case EXECUTE:
                    commandResponse = this.agentCommandExecutor.executeCommand(agentCommandRequest);
                    break;
                default:
                    System.err.println(
                            "Unknown request [" + requestMethod + "] " + requestPath + " - " + agentCommandRequest);

                    commandResponse = new AgentCommandResponse();
                    commandResponse.setMessage("unknown command: " + agentCommandRequest.getCommand());
                    commandResponse.setResponseType(ResponseType.FAILED);
                    break;
            }
            String responseBody = objectMapper.writeValueAsString(commandResponse);
            return newFixedLengthResponse(Response.Status.OK, "application/json", responseBody);
        } catch (Exception e) {
            e.printStackTrace();
            AgentCommandErrorResponse agentCommandErrorResponse = new AgentCommandErrorResponse(e.getMessage());
            String errorResponseBody = null;
            try {
                errorResponseBody = objectMapper.writeValueAsString(agentCommandErrorResponse);
            } catch (JsonProcessingException ex) {
                return newFixedLengthResponse("{\"message\": \"" + ex.getMessage() + "\"}");
            }

            return newFixedLengthResponse(errorResponseBody);
        }

    }

    public void setAgentCommandExecutor(AgentCommandExecutor agentCommandExecutor) {
        this.agentCommandExecutor = agentCommandExecutor;
    }
}
