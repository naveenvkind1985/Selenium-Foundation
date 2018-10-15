package com.nordstrom.automation.selenium;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Map;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.openqa.grid.internal.utils.configuration.GridHubConfiguration;
import org.openqa.grid.internal.utils.configuration.GridNodeConfiguration;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.json.JsonInput;
import com.nordstrom.automation.settings.SettingsCore;

/**
 * This class declares settings and methods related to WebDriver and Grid configuration specific to the Selenium 3 API.
 * 
 * @see SettingsCore
 */
@SuppressWarnings({"squid:S1200", "squid:S2972", "squid:MaximumInheritanceDepth"})
public class SeleniumConfig extends AbstractSeleniumConfig {
    
    private static final String JSON_HEAD = "{ \"capabilities\": [";
    private static final String JSON_TAIL = "] }";
    
    static {
        try {
            SELENIUM_CONFIG = new SeleniumConfig();
        } catch (ConfigurationException | IOException e) {
            throw new RuntimeException("Failed to instantiate settings", e); //NOSONAR
        }
    }
    
    private GridNodeConfiguration nodeConfig;
    private GridHubConfiguration hubConfig;
    
    /**
     * Instantiate a <b>Selenium Foundation</b> configuration object.
     * 
     * @throws ConfigurationException If a failure is encountered while initializing this configuration object.
     * @throws IOException If a failure is encountered while reading from a configuration input stream.
     */
    public SeleniumConfig() throws ConfigurationException, IOException {
        super();
    }

    /**
     * Get the Selenium configuration object.
     * 
     * @return Selenium configuration object
     */
    public static SeleniumConfig getConfig() {
        return (SeleniumConfig) SELENIUM_CONFIG;
    }
    
    /**
     * Get the Selenium Grid node configuration.
     * 
     * @return Selenium Grid node configuration
     */
    public GridNodeConfiguration getNodeConfig() {
        if (nodeConfig == null) {
            nodeConfig = GridNodeConfiguration.loadFromJSON(getNodeConfigPath());
            nodeConfig = resolveNodeSettings(nodeConfig);
        }
        return nodeConfig;
    }
    
    /**
     * Get the arguments needed to launch a local Selenium Grid node.
     * 
     * @return array of node launch arguments
     */
    @Override
    public String[] getNodeArgs() {
        if (nodeArgs == null) {
            String configPath = getNodeConfigPath();
            GridNodeConfiguration config = getNodeConfig();
            nodeArgs = new String[] {"-role", "node", "-nodeConfig", configPath, "-host",
                    config.host, "-port", config.port.toString(), "-hub", config.hub,
                    "-servlet", "org.openqa.grid.web.servlet.LifecycleServlet"};
        }
        return Arrays.copyOf(nodeArgs, nodeArgs.length);
    }

    /**
     * Resolve Selenium Grid node settings for host, port, and hub.
     * 
     * @param nodeConfig node configuration with unresolved settings
     * @return node configuration with resolved settings
     */
    private GridNodeConfiguration resolveNodeSettings(final GridNodeConfiguration nodeConfig) {
        String nodeHost = getString(SeleniumSettings.NODE_HOST.key());
        if (nodeHost != null) {
            nodeConfig.host = nodeHost;
        }
        if (nodeConfig.host == null) {
            nodeConfig.host = getLocalHost();
        }
        
        nodeConfig.port = getInteger(SeleniumSettings.NODE_PORT.key(), null);
        nodeConfig.hub = "http://" + getHubConfig().host + ":" + getHubConfig().port + "/grid/register/";
        
        return nodeConfig;
    }
    
    /**
     * Get the Selenium Grid hub configuration.
     * 
     * @return Selenium Grid hub configuration
     */
    public GridHubConfiguration getHubConfig() {
        if (hubConfig == null) {
            hubConfig = GridHubConfiguration.loadFromJSON(getHubConfigPath());
            hubConfig = resolveHubSettings(hubConfig);
        }
        return hubConfig;
    }
    
    /**
     * Get the arguments needed to launch a local Selenium Grid hub.
     * 
     * @return array of hub launch arguments
     */
    public String[] getHubArgs() {
        if (hubArgs == null) {
            String configPath = getHubConfigPath();
            GridHubConfiguration config = getHubConfig();
            hubArgs = new String[] {"-role", "hub", "-hubConfig", configPath, 
                    "-host", config.host, "-port", config.port.toString()};
        }
        return Arrays.copyOf(hubArgs, hubArgs.length);
    }
    
    /**
     * Resolve Selenium Grid hub settings for host and port.
     * 
     * @param hubConfig node configuration with unresolved settings
     * @return hub configuration with resolved settings
     */
    private GridHubConfiguration resolveHubSettings(final GridHubConfiguration hubConfig) {
        String hubHost = getString(SeleniumSettings.HUB_HOST.key());
        if (hubHost != null) {
            hubConfig.host = hubHost;
        }
        if (hubConfig.host == null) {
            hubConfig.host = getLocalHost();
        }
        
        Integer hubPort = getInteger(SeleniumSettings.HUB_PORT.key(), null);
        if (hubPort != null) {
            hubConfig.port = hubPort;
        }
        
        return hubConfig;
    }

    /**
     * Convert the configured browser specification from JSON to {@link Capabilities} object.
     * 
     * @return {@link Capabilities} object for the configured browser specification
     */
    public Capabilities getBrowserCaps() {
        if (browserCaps == null) {
            String jsonStr = getJsonForName(getString(SeleniumSettings.BROWSER_NAME.key()));
            
            if (jsonStr == null) {
                jsonStr = getString(SeleniumSettings.BROWSER_CAPS.key());
            }
            
            JsonInput input = new Json().newInput(new StringReader(JSON_HEAD + jsonStr + JSON_TAIL));
            GridNodeConfiguration config = GridNodeConfiguration.loadFromJSON(input);
            browserCaps = config.capabilities.get(0);
        }
        return browserCaps;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Map<String, String> getDefaults() {
        Map<String, String> defaults = super.getDefaults();
        defaults.put(SeleniumSettings.HUB_PORT.key(), "4445");
        defaults.put(SeleniumSettings.NODE_PORT.key(), "5556");
        defaults.put(SeleniumSettings.NODE_CONFIG.key(), "nodeConfig-s3.json");
        return defaults;
    }
}
