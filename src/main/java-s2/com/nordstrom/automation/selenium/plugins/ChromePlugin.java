package com.nordstrom.automation.selenium.plugins;

import java.util.Map;

import com.nordstrom.automation.selenium.DriverPlugin;

public class ChromePlugin implements DriverPlugin {
    
    /**
     * <b>org.openqa.selenium.chrome.ChromeDriver</b>
     * 
     * <pre>&lt;dependency&gt;
     *    &lt;groupId&gt;org.seleniumhq.selenium&lt;/groupId&gt;
     *    &lt;artifactId&gt;selenium-chrome-driver&lt;/artifactId&gt;
     *    &lt;version&gt;2.53.0&lt;/version&gt;
     *&lt;/dependency&gt;</pre>
     */
    private static final String[] DEPENDENCY_CONTEXTS = {
                    "org.openqa.selenium.chrome.ChromeDriver",
                    "org.apache.commons.exec.Executor",
                    "org.openqa.selenium.os.Kernel32",
                    "com.sun.jna.platform.win32.Kernel32",
                    "com.sun.jna.win32.StdCallLibrary"};
    
    /**
     * {@inheritDoc}
     */
    @Override
    public String[] getDependencyContexts() {
        return DEPENDENCY_CONTEXTS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCapabilities() {
        return ChromeCaps.getCapabilities();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, String> getPersonalities() {
        return ChromeCaps.getPersonalities();
    }

}