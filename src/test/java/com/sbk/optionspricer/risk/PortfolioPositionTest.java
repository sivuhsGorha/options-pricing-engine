package com.sbk.optionspricer.risk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PortfolioPositionTest {
    
    private final Path ledgerPath = Paths.get("target", "fill_ledger.csv");

    @BeforeEach
    void setup() throws IOException {
        Files.deleteIfExists(ledgerPath);
        FillLedger.init();
    }

    @AfterEach
    void teardown() throws IOException {
        Files.deleteIfExists(ledgerPath);
    }

    @Test
    void testInvalidSymbol() {
        assertThrows(IllegalArgumentException.class, () -> {
            new PortfolioPosition("", 1, 100);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            new PortfolioPosition(null, 1, 100);
        });
    }

    @Test
    void testInvalidMultiplier() {
        assertThrows(IllegalArgumentException.class, () -> {
            new PortfolioPosition("SPY", 1, -1);
        });
    }

    @Test
    void testInvalidGreeks() {
        PortfolioPosition pos = new PortfolioPosition("SPY", 1, 100);
        assertThrows(IllegalArgumentException.class, () -> {
            pos.updateGreeks(Double.NaN, 0.0, 0.0);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            pos.updateGreeks(0.0, Double.POSITIVE_INFINITY, 0.0);
        });
    }

    @Test
    void testIntOverflow() {
        PortfolioPosition pos = new PortfolioPosition("SPY", Integer.MAX_VALUE, 100);
        assertThrows(ArithmeticException.class, () -> {
            pos.addQuantity(1);
        });
        
        pos.updateGreeks(1.0, 0.0, 0.0);
        assertThrows(ArithmeticException.class, () -> {
            pos.getPositionDelta();
        });
    }

    @Test
    void testLedgerAppend() throws IOException {
        PortfolioPosition pos = new PortfolioPosition("SPY", 10, 100);
        pos.addQuantity(5);
        
        assertTrue(Files.exists(ledgerPath));
        List<String> lines = Files.readAllLines(ledgerPath);
        assertTrue(lines.size() >= 3); // header + 2 fills
        assertTrue(lines.get(1).contains("SPY,10,100"));
        assertTrue(lines.get(2).contains("SPY,5,100"));
    }
}
