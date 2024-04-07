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

    @Test
    public void testSourceFileAvailability() {
        APIResponse response = page.request().get("http://127.0.0.1:8085/v1/css/components.default.css");
        assertEquals(200, response.status(), "components.default.css should be available");

        response = page.request().get("http://127.0.0.1:8085/v1/img/logo.png");
        assertEquals(200, response.status(), "logo.png should be available");

        response = page.request().get("http://127.0.0.1:8085/v1/js/components.min.js");
        assertEquals(200, response.status(), "components.min.js should be available");

        response = page.request().get("http://127.0.0.1:8085/v1/js/nodel.js");
        assertEquals(200, response.status(), "nodel.js should be available");
    }

    @Test
    public void testFontFamily() {
        String fontFamily = page.evaluate("() => window.getComputedStyle(document.body).getPropertyValue('font-family')").toString();
        assertTrue(fontFamily.contains("Roboto"), "Font family should include Roboto");
    }

    @Test
    public void testBootstrapLayout() {
        assertNotNull(page.querySelector("div.row > div.col-sm-12"), "Bootstrap row and column should be present");
    }

    @Test
    public void testNodelAddElement() {
        assertNotNull(page.querySelector(".nodel-add"), ".nodel-add should be present");
    }

    @Test
    public void testListGroupItems() {
        assertNotNull(page.querySelector(".list-group-item"), "list-group-item elements should be present");
    }

    @Test
    public void testListGroupItemBorder() {
        String border = page.evaluate("() => window.getComputedStyle(document.querySelector('.list-group-basic .list-group-item')).getPropertyValue('border')").toString();
        assertEquals("none", border, "list-group-item elements should have border: none");
    }

    @Test
    public void testNodelAddButtonMargin() {
        String marginBottom = page.evaluate("() => window.getComputedStyle(document.querySelector('.nodel-add .btn')).getPropertyValue('margin-bottom')").toString();
        assertEquals("5px", marginBottom, ".nodel-add .btn should have margin-bottom: 5px");
    }

    @AfterAll
    public static void tearDown() {
        if (browser != null) {
            browser.close();
        }
        playwright.close();
    }
}