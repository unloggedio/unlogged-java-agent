package com.videobug.agent;

import com.videobug.agent.weaver.RuntimeWeaver;

public class Constants {
    public static final String AGENT_VERSION = RuntimeWeaver.class.getPackage().getImplementationVersion();
}
