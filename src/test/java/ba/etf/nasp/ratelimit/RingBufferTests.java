package ba.etf.nasp.ratelimit;

import java.util.ArrayDeque;
import java.util.NoSuchElementException;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RingBufferTests {

    @Test
    void startsEmpty() {
        RingBuffer<String> buffer = new RingBuffer<>(3);
        assertThat(buffer.isEmpty()).isTrue();
        assertThat(buffer.isFull()).isFalse();
        assertThat(buffer.size()).isZero();
        assertThat(buffer.capacity()).isEqualTo(3);
    }

    @Test
    void returnsElementsInInsertionOrder() {
        RingBuffer<String> buffer = new RingBuffer<>(3);
        buffer.offer("a");
        buffer.offer("b");
        buffer.offer("c");
        assertThat(buffer.poll()).isEqualTo("a");
        assertThat(buffer.poll()).isEqualTo("b");
        assertThat(buffer.poll()).isEqualTo("c");
        assertThat(buffer.isEmpty()).isTrue();
    }

    @Test
    void rejectsOfferWhenFullWithoutChangingContents() {
        RingBuffer<String> buffer = new RingBuffer<>(2);
        assertThat(buffer.offer("a")).isTrue();
        assertThat(buffer.offer("b")).isTrue();
        assertThat(buffer.isFull()).isTrue();
        assertThat(buffer.offer("c")).isFalse();
        assertThat(buffer.size()).isEqualTo(2);
        assertThat(buffer.poll()).isEqualTo("a");
        assertThat(buffer.poll()).isEqualTo("b");
    }

    @Test
    void wrapsAroundTheEndOfTheArray() {
        RingBuffer<Integer> buffer = new RingBuffer<>(3);
        buffer.offer(1);
        buffer.offer(2);
        buffer.poll();
        buffer.poll();
        // head is now at index 2, so these three writes wrap to indexes 2, 0 and 1.
        buffer.offer(3);
        buffer.offer(4);
        buffer.offer(5);
        assertThat(buffer.isFull()).isTrue();
        assertThat(buffer.poll()).isEqualTo(3);
        assertThat(buffer.poll()).isEqualTo(4);
        assertThat(buffer.poll()).isEqualTo(5);
    }

    @Test
    void behavesLikeABoundedQueueOverManyRandomOperations() {
        RingBuffer<Integer> buffer = new RingBuffer<>(5);
        ArrayDeque<Integer> model = new ArrayDeque<>();
        Random random = new Random(7);
        for (int i = 0; i < 10_000; i++) {
            if (random.nextBoolean()) {
                boolean accepted = buffer.offer(i);
                assertThat(accepted).isEqualTo(model.size() < 5);
                if (accepted) {
                    model.addLast(i);
                }
            } else if (!model.isEmpty()) {
                assertThat(buffer.poll()).isEqualTo(model.removeFirst());
            }
            assertThat(buffer.size()).isEqualTo(model.size());
        }
    }

    @Test
    void rejectsInvalidUse() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RingBuffer<>(0));
        RingBuffer<String> buffer = new RingBuffer<>(1);
        assertThatThrownBy(buffer::poll).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> buffer.offer(null)).isInstanceOf(NullPointerException.class);
    }
}
