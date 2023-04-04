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

    public AgentCommandServer(int port) {
        super(port);
    }

    public AgentCommandServer(String hostname, int port) {
        super(hostname, port);
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
        try {
            AgentCommandRequest agentCommandRequest = objectMapper.readValue(
                    postBody != null ? postBody : requestBodyText,
                    AgentCommandRequest.class);
            AgentCommandResponse commandResponse = this.agentCommandExecutor.executeCommand(agentCommandRequest);
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
