package dev.nanonative.railix.stdlib.text;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.Locale;

/** Lowercases one string with an explicit BCP 47 language-tag configuration. */
public final class LowercaseStep {
    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    private static final Locale AZERI = Locale.forLanguageTag("az");
    private static final Locale LITHUANIAN = Locale.forLanguageTag("lt");
    private static final Locale THAI = Locale.forLanguageTag("th");

    private LowercaseStep() {
    }

    public static StepDefinition definition() {
        return StepDefinition.named("text.lowercase", "1.2.0")
                .kind(StepDefinition.Kind.NORMALIZER)
                .config(
                        "languageTag",
                        ValueShape.string(),
                        StepDefinition.ConfigFormat.LANGUAGE_TAG,
                        RailixValue.string("und")
                )
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output(
                        "text",
                        RailixValue.string(input.string("text").toLowerCase(
                                caseLocale(input.configString("languageTag"))
                        ))
                ));
    }

    private static Locale caseLocale(final String languageTag) {
        if (effectiveLanguage(languageTag, "tr")) {
            return TURKISH;
        }
        if (effectiveLanguage(languageTag, "az")) {
            return AZERI;
        }
        if (effectiveLanguage(languageTag, "lt")) {
            return LITHUANIAN;
        }
        return effectiveLanguage(languageTag, "th") ? THAI : Locale.ROOT;
    }

    private static boolean effectiveLanguage(final String languageTag, final String language) {
        if (!languageTag.regionMatches(true, 0, language, 0, language.length())) {
            return false;
        }
        if (languageTag.length() == language.length()) {
            return true;
        }
        return languageTag.charAt(language.length()) == '-'
                && !hasExtlang(languageTag, language.length() + 1);
    }

    private static boolean hasExtlang(final String languageTag, final int start) {
        final int separator = languageTag.indexOf('-', start);
        final int end = separator < 0 ? languageTag.length() : separator;
        if (end - start != 3) {
            return false;
        }
        return Character.isLetter(languageTag.charAt(start));
    }
}
