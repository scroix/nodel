package org.nodel;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ActiveNavItemSeleniumTest {

    @Test
    public void testActiveNavigationItem() {
        WebDriver driver = WebDriverSingleton.getDriver();
        driver.get("http://127.0.0.1:8085");
        String activeNavItemText = driver.findElement(By.cssSelector(".nav.navbar-nav .active")).getText();
        assertEquals("Locals", activeNavItemText, "Active navigation item should be 'Locals'");
    }
}
