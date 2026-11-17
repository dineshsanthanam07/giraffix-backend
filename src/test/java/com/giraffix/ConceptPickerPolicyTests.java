package com.giraffix;

import com.giraffix.selection.ConceptPickerRules;
import com.giraffix.selection.SelectionPolicy;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConceptPickerPolicyTests {
    @Test void defaultsMatchRequestedPickerAndSettingsAreValidated() {
        var policy=new SelectionPolicy();policy.validate();
        assertThat(ConceptPickerRules.enabled(policy.snapshot())).isTrue();
        assertThat(policy.batchSize).isEqualTo(5);assertThat(policy.threshold).isEqualTo(80);assertThat(policy.maxRounds).isEqualTo(3);
        policy.batchSize=20;policy.threshold=90;policy.validate();
        assertThat(policy.snapshot().batchSize()).isEqualTo(20);assertThat(policy.snapshot().threshold()).isEqualTo(90);
        policy.threshold=20;policy.validate();
        policy.batchSize=0;assertThatThrownBy(policy::validate).isInstanceOf(IllegalArgumentException.class);
        policy.batchSize=5;policy.threshold=101;assertThatThrownBy(policy::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
