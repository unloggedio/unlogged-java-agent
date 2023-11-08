package io.unlogged;

import io.unlogged.weaver.RuntimeWeaver;

public class Constants {
    public static final String AGENT_VERSION = RuntimeWeaver.class.getPackage().getImplementationVersion();
}
