package ba.etf.nasp.ratelimit;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

// A real server, because queued requests are released through servlet async dispatch.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rate-limit.algorithm=leaky-bucket-queue", "rate-limit.capacity-tokens=4",
        "rate-limit.window-seconds=1", "server.tomcat.threads.max=2"})
class LeakyBucketQueueHttpTests {

    private record Result(int status, String body, long millis) {
    }

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    void burstIsShapedIntoASteadyStreamAndOverflowIsRejected() {
        long start = System.nanoTime();
        List<CompletableFuture<Result>> burst = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/hello"))
                    .header("X-API-Key", "burst-client").build();
            burst.add(http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(r -> new Result(r.statusCode(), r.body(), (System.nanoTime() - start) / 1_000_000)));
        }
        List<Result> results = burst.stream().map(CompletableFuture::join)
                .sorted(Comparator.comparingLong(Result::millis)).toList();

        List<Result> rejected = results.stream().filter(r -> r.status() == 429).toList();
        List<Result> passed = results.stream().filter(r -> r.status() == 200).toList();
        assertThat(rejected).hasSize(2).allMatch(r -> r.millis() < 200);
        assertThat(passed).hasSize(4).allMatch(r -> r.body().equals("ok"));

        // Released one leak interval (250 ms) apart. Only two server threads exist, so four
        // requests waiting at once also shows that queued requests do not hold threads.
        for (int i = 1; i < passed.size(); i++) {
            assertThat(passed.get(i).millis() - passed.get(i - 1).millis()).isBetween(200L, 400L);
        }
    }
}
