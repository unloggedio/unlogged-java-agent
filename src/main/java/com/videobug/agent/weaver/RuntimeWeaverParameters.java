package com.videobug.agent.weaver;

import com.videobug.agent.weaver.RuntimeWeaver.Mode;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Parameters for controlling the behavior of a runtime weaver.
 */
public class RuntimeWeaverParameters {


    private static final String[] SYSTEM_PACKAGES = {"sun/", "com/sun/", "java/", "javax/"};
    private static final String ARG_SEPARATOR = ",";
    private static final String SELOGGER_DEFAULT_OUTPUT_DIR =
            System.getProperty("user.home") + "/.videobug/sessions/selogger-output-{time}";

    private static final Pattern timePattern = Pattern.compile(".*(\\{time:([^}]+)\\}).*");
    /**
     * Package/class names (prefix) excluded from logging
     */
    private final ArrayList<String> excludedNames;
    /**
     * Package/class names (prefix) included in logging
     */
    private final ArrayList<String> includedNames;
    /**
     * Location names (substring) excluded from logging
     */
    private final ArrayList<String> excludedLocations;
    private String authToken = "localhost-token";
    private String output_dirname = SELOGGER_DEFAULT_OUTPUT_DIR;
    private String serverAddress;
    private String username;
    private String password;
    private String weaveOption = WeaveConfig.KEY_RECORD_ALL;
    private boolean outputJson = false;
    /**
     * Dump woven class files (mainly for debugging)
     */
    private boolean dumpClass = false;
    /**
     * The number of events recorded per code location
     */
    private int bufferSize = 32;
    /**
     * Strategy to keep objects on memory
     */
//    private LatestEventLogger.ObjectRecordingStrategy keepObject = LatestEventLogger.ObjectRecordingStrategy.Strong;
    /**
     * If true, automatic filtering for security manager classes is disabled
     */
    private boolean weaveSecurityManagerClass = false;
    private Mode mode = Mode.Testing;
    private int filesPerIndex = 100;

    public RuntimeWeaverParameters(String args) {
        if (args == null) args = "";
        String[] a = args.split(ARG_SEPARATOR);

        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmssSSS");
        output_dirname = output_dirname.replace("{time}", f.format(new Date()));

        excludedNames = new ArrayList<String>();
        includedNames = new ArrayList<String>();
        excludedLocations = new ArrayList<String>();
        for (String pkg : SYSTEM_PACKAGES) excludedNames.add(pkg);

        for (String arg : a) {
            if (arg.startsWith("output=")) {
                output_dirname = arg.substring("output=".length());
                if (output_dirname.contains("{time}")) {
                    output_dirname = output_dirname.replace("{time}", f.format(new Date()));
                } else {
                    Matcher m = timePattern.matcher(output_dirname);
                    if (m.matches()) {
                        output_dirname = output_dirname.replace(m.group(1), f.format(new Date()));
                    }
                }
            } else if (arg.startsWith("weave=")) {
                weaveOption = arg.substring("weave=".length());
            } else if (arg.startsWith("server=")) {
                serverAddress = arg.substring("server=".length());
            } else if (arg.startsWith("token=")) {
                authToken = arg.substring("token=".length());
            } else if (arg.startsWith("username=")) {
                username = arg.substring("username=".length());
            } else if (arg.startsWith("filePerIndex=")) {
                filesPerIndex = Integer.parseInt(arg.substring("filePerIndex=".length()));
            } else if (arg.startsWith("password=")) {
                password = arg.substring("password=".length());
            } else if (arg.startsWith("dump=")) {
                String classDumpOption = arg.substring("dump=".length());
                dumpClass = classDumpOption.equalsIgnoreCase("true");
            } else if (arg.startsWith("size=")) {
                bufferSize = Integer.parseInt(arg.substring("size=".length()));
                if (bufferSize < 4) bufferSize = 4;
            } else if (arg.startsWith("weavesecuritymanager=")) {
                weaveSecurityManagerClass = Boolean.parseBoolean(arg.substring("weavesecuritymanager=".length()));
            } else if (arg.startsWith("json=")) {
                String param = arg.substring("json=".length());
                outputJson = param.equalsIgnoreCase("true");
            }  else if (arg.startsWith("e=")) {
                String prefix = arg.substring("e=".length());
                if (prefix.length() > 0) {
                    prefix = prefix.replace('.', '/');
                    excludedNames.add(prefix);
                }
            } else if (arg.startsWith("i=")) {
                String prefix = arg.substring("i=".length());
                if (prefix.length() > 0) {
                    prefix = prefix.replace('.', '/');
                    includedNames.add(prefix);
                }
            } else if (arg.startsWith("exlocation=")) {
                String location = arg.substring("exlocation=".length());
                if (location.length() > 0) {
                    excludedLocations.add(location);
                }
            } else if (arg.startsWith("format=")) {
                String opt = arg.substring("format=".length()).toLowerCase();
                if (opt.startsWith("freq")) {
                    mode = Mode.Frequency;
                } else if (opt.startsWith("discard")) {
                    mode = Mode.Discard;
                } else if (opt.startsWith("omni") || opt.startsWith("stream")) {
                    mode = Mode.Stream;
//                } else if (opt.startsWith("single")) {
//                    mode = Mode.Single;
                } else if (opt.startsWith("replay")) {
                    mode = Mode.PerThread;
                } else if (opt.startsWith("testing")) {
                    mode = Mode.Testing;
                } else if (opt.startsWith("network")) {
                    mode = Mode.Network;
                } else if (opt.startsWith("latest") || opt.startsWith("nearomni") || opt.startsWith("near-omni")) {
                    mode = Mode.FixedSize;
                }
            }
        }
    }

    public String getOutputDirname() {
        return output_dirname;
    }

    public String getWeaveOption() {
        return weaveOption;
    }

    public boolean isDumpClassEnabled() {
        return dumpClass;
    }

    public Mode getMode() {
        return mode;
    }

    public int getBufferSize() {
        return bufferSize;
    }
//
//    public LatestEventLogger.ObjectRecordingStrategy getObjectRecordingStrategy() {
//        return keepObject;
//    }

    public boolean isOutputJsonEnabled() {
        return outputJson;
    }

    public boolean isWeaveSecurityManagerClassEnabled() {
        return weaveSecurityManagerClass;
    }

    public ArrayList<String> getExcludedNames() {
        return excludedNames;
    }

    public ArrayList<String> getIncludedNames() {
        return includedNames;
    }


    public String getServerAddress() {

        return serverAddress;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public ArrayList<String> getExcludedLocations() {
        return excludedLocations;
    }

    public String getAuthToken() {
        return authToken;
    }

    public int getFilesPerIndex() {
        return filesPerIndex;
    }

    public void setFilesPerIndex(int filesPerIndex) {
        this.filesPerIndex = filesPerIndex;
    }
}
