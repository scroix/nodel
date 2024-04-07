package org.nodel;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

public class PlaywrightTests {

    private static Playwright playwright;
    private static Browser browser;
    private static Page page;

    @BeforeAll
    public static void setup() {
        playwright = Playwright.create();
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions();
        // Add any browser options here if necessary
        browser = playwright.chromium().launch(options);
        BrowserContext context = browser.newContext();
        // Customize the context if needed (viewport size, user agent, etc.)
        page = context.newPage();

        // Wait for the application to start up
        page.navigate("http://127.0.0.1:8085");
        page.waitForSelector(".navbar");
    }

    @Test
    public void testNavigationBarPresence() {
        assertNotNull(page.querySelector(".navbar"), "Navigation bar should be present");
    }

    @Test
    public void testActiveNavigationItem() {
        String activeNavItemText = page.textContent(".nav.navbar-nav .active");
        assertEquals("Locals", activeNavItemText, "Active navigation item should be 'Locals'");
    }

    @AfterAll
    public static void tearDown() {
        if (browser != null) {
            browser.close();
        }
        playwright.close();
    }
}