package com.giraffix;

import com.giraffix.selection.*;
import com.giraffix.selection.SelectionModels.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ScorePolicyTests {
    final SelectionPolicy policy=new SelectionPolicy();final ScorePolicy score=new ScorePolicy();
    Evaluation value(Ratings ratings){return new Evaluation(UUID.randomUUID(),1,"Exact execution",ratings,List.of(),List.of(),
            ScorePolicy.DIMENSIONS.stream().map(d->new Reason(d,"SUPPORTED",List.of("/intent"))).toList(),false);}
    @Test void documentedArithmeticAndFloors(){
        assertThat(score.score(value(new Ratings(4,4,3,3,3)),policy.snapshot()).score()).isEqualTo(88.75);
        assertThat(score.score(value(new Ratings(4,4,3,3,3)),policy.snapshot()).passes()).isTrue();
        assertThat(score.score(value(new Ratings(4,2,4,4,4)),policy.snapshot()).passes()).isFalse();
        assertThat(score.score(value(new Ratings(2,4,3,4,4)),policy.snapshot()).score()).isEqualTo(80);
        assertThat(score.score(value(new Ratings(2,4,3,4,4)),policy.snapshot()).passes()).isFalse();
        assertThat(score.score(value(new Ratings(3,3,3,3,3)),policy.snapshot()).passes()).isFalse();
    }
    @Test void exactBoundaryUsesIntegerArithmetic(){
        var v=value(new Ratings(3,3,4,3,3));assertThat(score.score(v,policy.snapshot()).score()).isEqualTo(80);
        assertThat(score.score(v,policy.snapshot()).passes()).isTrue();policy.threshold=81;assertThat(score.score(v,policy.snapshot()).passes()).isFalse();
    }
    @Test void rejectsInvalidRatingsAndMissingUncertainty(){
        assertThatThrownBy(()->score.score(value(new Ratings(5,4,4,4,4)),policy.snapshot())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->score.score(value(new Ratings(null,4,4,4,4)),policy.snapshot())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void validatesConfigAndCostEnvelope(){
        policy.validate();policy.weights=List.of(30,30,30,30,30);assertThatThrownBy(policy::validate).isInstanceOf(IllegalArgumentException.class);
        policy.weights=List.of(30,25,20,15,10);policy.maxAttemptCostMicros=1;assertThatThrownBy(policy::validate).isInstanceOf(IllegalArgumentException.class);
    }
}
