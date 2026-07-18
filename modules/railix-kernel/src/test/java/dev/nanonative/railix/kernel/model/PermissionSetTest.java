package dev.nanonative.railix.kernel.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionSetTest {

    @Test
    void shouldExposeEmptyNoneFactory() {
        final PermissionSet permissionSet = PermissionSet.none();

        assertThat(permissionSet.requested()).isEmpty();
        assertThat(permissionSet.granted()).isEmpty();
        assertThat(permissionSet.decisions()).isEmpty();
    }

    @Test
    void shouldCopyRequestedGrantedAndDecisions() {
        final Map<String, List<String>> requested = new HashMap<>(Map.of(
                "settings.secret", new ArrayList<>(List.of("settings.database.password"))
        ));
        final Map<String, List<String>> granted = new HashMap<>(Map.of(
                "settings.secret", new ArrayList<>(List.of("settings.database.password"))
        ));
        final List<PermissionSet.Decision> decisions = new ArrayList<>(List.of(
                new PermissionSet.Decision(
                        "settings.secret",
                        "settings.database.password",
                        PermissionSet.DecisionResult.GRANTED,
                        "explicit grant"
                )
        ));

        final PermissionSet permissionSet = new PermissionSet(requested, granted, decisions);

        requested.clear();
        granted.clear();
        decisions.clear();

        assertThat(permissionSet.requested()).containsKey("settings.secret");
        assertThat(permissionSet.granted()).containsKey("settings.secret");
        assertThat(permissionSet.decisions()).hasSize(1);
    }

    @Test
    void shouldRejectBlankDecisionFields() {
        assertThatThrownBy(() -> new PermissionSet.Decision(
                " ",
                "settings.database.password",
                PermissionSet.DecisionResult.DENIED,
                "nope"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permission");
    }
}
