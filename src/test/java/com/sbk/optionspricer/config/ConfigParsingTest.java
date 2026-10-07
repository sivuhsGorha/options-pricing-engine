package com.sbk.optionspricer.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The config reader accepts what the documentation says it accepts and names the line or key of anything else. */
class ConfigParsingTest {

    private static Path yaml(String content) throws Exception {
        Path file = Files.createTempFile("config-parsing", ".yaml");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void inlineCommentsAndQuotedStringsAreHandled() throws Exception {
        ConfigManager config = ConfigManager.fromFile(yaml("""
                strategy:
                  trigger_pct: 0.00001   # test setting: trade on almost any move
                execution:
                  symbol: "SPY"
                  note: 'a # b'          # the hash inside quotes is part of the value
                  sources: ["yahoo_finance", 'other']
                  scientific: 1e-5
                """));

        assertEquals(1e-5, config.getDouble("strategy.trigger_pct", 0), 0.0, "the comment must not become part of the number");
        assertEquals("SPY", config.getString("execution.symbol", null));
        assertEquals("a # b", config.getString("execution.note", null));
        assertEquals(List.of("yahoo_finance", "other"), config.get("execution.sources"));
        assertEquals(1e-5, config.getDouble("execution.scientific", 0), 0.0);
    }

    @Test
    void tabsBlockListsMissingKeysAndDuplicatesAreErrorsThatNameTheLine() throws Exception {
        ConfigException tabs = assertThrows(ConfigException.class, () -> ConfigManager.fromFile(yaml("risk:\n\tmax_delta: 5\n")));
        assertTrue(tabs.getMessage().contains("line 2") && tabs.getMessage().contains("tab"), tabs.getMessage());

        ConfigException noKey = assertThrows(ConfigException.class, () -> ConfigManager.fromFile(yaml("risk:\n  max_delta 5\n")));
        assertTrue(noKey.getMessage().contains("line 2"), noKey.getMessage());

        ConfigException blockList = assertThrows(ConfigException.class, () -> ConfigManager.fromFile(yaml("market_data:\n  sources:\n    - yahoo_finance\n")));
        assertTrue(blockList.getMessage().contains("line 3") && blockList.getMessage().contains("[a, b]"), blockList.getMessage());

        ConfigException duplicate = assertThrows(ConfigException.class, () -> ConfigManager.fromFile(yaml("risk:\n  max_delta: 1\n  max_delta: 2\n")));
        assertTrue(duplicate.getMessage().contains("line 3") && duplicate.getMessage().contains("duplicate"), duplicate.getMessage());
    }

    @Test
    void aValueOfTheWrongTypeNamesTheFileTheKeyAndTheValue() throws Exception {
        Path file = yaml("risk:\n  max_delta: lots\n  enabled: maybe\ndashboard:\n  port: 8080.5\n");
        ConfigManager config = ConfigManager.fromFile(file);

        ConfigException notNumber = assertThrows(ConfigException.class, () -> config.getDouble("risk.max_delta", 0));
        assertTrue(notNumber.getMessage().contains(file.getFileName().toString()), notNumber.getMessage());
        assertTrue(notNumber.getMessage().contains("risk.max_delta") && notNumber.getMessage().contains("lots"), notNumber.getMessage());
        assertThrows(ConfigException.class, () -> config.getBoolean("risk.enabled", true));
        assertThrows(ConfigException.class, () -> config.getInt("dashboard.port", 1));
        assertEquals(7, config.getInt("dashboard.missing", 7), "an absent key still yields the default");
    }

    @Test
    void aFileThatExistsButCannotBeReadIsAnErrorNotSilentDefaults() throws Exception {
        Path directory = Files.createTempDirectory("config-parsing-dir");

        ConfigException e = assertThrows(ConfigException.class, () -> ConfigManager.fromFile(directory));

        assertTrue(e.getMessage().contains("cannot read"), e.getMessage());
    }

    @Test
    void theValidatorReportsEveryProblemAsAMessageInsteadOfStoppingAtTheFirst() throws Exception {
        ConfigManager config = ConfigManager.fromFile(yaml("""
                market_data:
                  refresh_interval_seconds: 900
                dashboard:
                  port: 8082
                strategy:
                  trigger_pct: 1.5
                  base_quantity: 0
                execution:
                  contract_multiplier: 0
                  slippage_bps: -1
                risk:
                  max_delta: lots
                """));

        List<String> errors = ConfigValidator.validate(config);

        String joined = String.join("\n", errors);
        for (String key : new String[]{"strategy.trigger_pct", "strategy.base_quantity", "execution.contract_multiplier", "execution.slippage_bps", "risk.max_delta"}) {
            assertTrue(joined.contains(key), "missing a report for " + key + " in:\n" + joined);
        }
        assertEquals(5, errors.size(), joined);
    }

    @Test
    void checkConfigSaysOkForAValidSetupAndListsEveryProblemOtherwise() throws Exception {
        Path valid = yaml("market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\n");
        Map<String, String> goodEnv = Map.of("API_SECRET", "0123456789abcdef0123456789abcdef0123456789", "OPERATOR_PASSWORD", "long-enough-password", "FINNHUB_KEY", "k");
        ByteArrayOutputStream okOut = new ByteArrayOutputStream();
        int okCode = ConfigCheck.run(valid, goodEnv::get, new PrintStream(okOut, true, StandardCharsets.UTF_8));
        String okText = okOut.toString(StandardCharsets.UTF_8);
        assertEquals(0, okCode, okText);
        assertTrue(okText.contains("OK") && okText.contains("FINNHUB_KEY"), okText);
        assertFalse(okText.contains("long-enough-password") || okText.contains("0123456789abcdef"), "secret values are never printed");

        Path invalid = yaml("market_data:\n  refresh_interval_seconds: 900\ndashboard:\n  port: 8082\nstrategy:\n  trigger_pct: 1.5\n");
        ByteArrayOutputStream badOut = new ByteArrayOutputStream();
        int badCode = ConfigCheck.run(invalid, key -> null, new PrintStream(badOut, true, StandardCharsets.UTF_8));
        String badText = badOut.toString(StandardCharsets.UTF_8);
        assertEquals(1, badCode, badText);
        assertTrue(badText.contains("strategy.trigger_pct") && badText.contains("API_SECRET") && badText.contains("OPERATOR_PASSWORD"), badText);
        assertTrue(badText.contains("SIMULATED"), "no market data keys must be called out: " + badText);

        ByteArrayOutputStream unparsable = new ByteArrayOutputStream();
        assertEquals(1, ConfigCheck.run(yaml("risk:\n\tmax_delta: 1\n"), goodEnv::get, new PrintStream(unparsable, true, StandardCharsets.UTF_8)));
        assertTrue(unparsable.toString(StandardCharsets.UTF_8).contains("line 2"));
    }
}
