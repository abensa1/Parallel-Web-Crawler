package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import javax.inject.Provider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
    private final Clock clock;
    private final Duration timeout;
    private final int popularWordCount;
    private final ForkJoinPool pool;
    private final List<Pattern> ignoredUrls;
    private final int maxDepth;
    private final PageParserFactory parserFactory;

    @Inject
    ParallelWebCrawler(
            Clock clock,
            @Timeout Duration timeout,
            @PopularWordCount int popularWordCount,
            @TargetParallelism int threadCount,
            @IgnoredUrls List<Pattern> ignoredUrls,
            @MaxDepth int maxDepth,
            PageParserFactory parserFactory) {
        this.clock = clock;
        this.timeout = timeout;
        this.popularWordCount = popularWordCount;
        this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
        this.ignoredUrls = ignoredUrls;
        this.maxDepth = maxDepth;
        this.parserFactory = parserFactory;
    }

    @Override
    public CrawlResult crawl(List<String> startingUrls) {
        Instant deadline = clock.instant().plus(timeout);
        Map<String, Integer> counts = Collections.synchronizedMap(new HashMap<>());
        Set<String> visitedUrls = Collections.synchronizedSet(new HashSet<>());
        for (String url : startingUrls) {
            pool.invoke(new InternalCrawler(url, deadline, maxDepth, counts, visitedUrls, clock, parserFactory, ignoredUrls));
        }

        if (counts.isEmpty()) {
            return new CrawlResult.Builder()
                    .setWordCounts(counts)
                    .setUrlsVisited(visitedUrls.size())
                    .build();
        }

        return new CrawlResult.Builder()
                .setWordCounts(WordCounts.sort(counts, popularWordCount))
                .setUrlsVisited(visitedUrls.size())
                .build();
    }

    public class InternalCrawler extends RecursiveTask<Boolean> {
        private final String url;
        private Instant deadline;
        private int maxDepth;
        private Map<String, Integer> counts;
        private Set<String> visitedUrls;
        private Clock clock;
        private PageParserFactory parserFactory;
        private List<Pattern> ignoredUrls;

        public InternalCrawler(String url, Instant deadline, int maxDepth, Map<String, Integer> counts, Set<String> visitedUrls, Clock clock, PageParserFactory parserFactory, List<Pattern> ignoredUrls) {
            this.url = url;
            this.deadline = deadline;
            this.maxDepth = maxDepth;
            this.counts = counts;
            this.visitedUrls = visitedUrls;
            this.clock = clock;
            this.parserFactory = parserFactory;
            this.ignoredUrls = ignoredUrls;
        }

        @Override
        protected Boolean compute() {
            if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
                return false;
            }
            for (Pattern pattern : ignoredUrls) {
                if (pattern.matcher(url).matches()) {
                    return false;
                }
            }
            if (!visitedUrls.add(url)) {
                return false;
            }
            visitedUrls.add(url);
            PageParser.Result result = parserFactory.get(url).parse();
            for (Map.Entry<String, Integer> e : result.getWordCounts().entrySet()) {
                counts.compute(e.getKey(), (k, v) -> (v == null) ? e.getValue() : e.getValue() + v);
            }
            List<InternalCrawler> subtasks = new ArrayList<>();
            for (String link : result.getLinks()) {
                subtasks.add(new InternalCrawler(link, deadline, maxDepth - 1, counts, visitedUrls, clock, parserFactory, ignoredUrls));
            }
            invokeAll(subtasks);
            return true;
        }
    }

    @Override
    public int getMaxParallelism() {
        return Runtime.getRuntime().availableProcessors();
    }
}