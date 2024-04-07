package org.nodel;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class NavigationSeleniumTest {

    @Test
    public void testNavigationBarPresence() {
        WebDriver driver = WebDriverSingleton.getDriver();
        driver.get("http://127.0.0.1:8085");
        assertNotNull(driver.findElement(By.cssSelector(".navbar")), "Navigation bar should be present");
    }
}
