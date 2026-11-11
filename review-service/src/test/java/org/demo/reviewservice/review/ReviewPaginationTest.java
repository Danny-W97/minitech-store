package org.demo.reviewservice.review;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.demo.platformsecurity.ListWindow;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class ReviewPaginationTest {
    @Autowired ReviewRepository repository;
    @Autowired ReviewService service;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory factory;

    @Test void aggregateIncludesOlderPagesWithoutLoadingEntities() {
        for (int i = 1; i <= 61; i++) {
            Review review = new Review(29L, (long)i, 29L, i == 61 ? 1 : 5, "Review", "Content");
            review.moderate(ReviewStatus.PUBLISHED, "Private note");
            repository.save(review);
        }
        repository.save(new Review(29L, 1000L, 29L, 1, "Pending", "Excluded"));
        entityManager.flush(); entityManager.clear();
        var stats = factory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        var summary = service.summary(29L);
        assertThat(summary.publishedCount()).isEqualTo(61);
        assertThat(summary.averageRating()).isEqualByComparingTo("4.93");
        assertThat(stats.getEntityLoadCount()).isZero();
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
        var first = service.published(29L, new ListWindow(0, 20));
        assertThat(first.items()).hasSize(20);
        assertThat(stats.getEntityLoadCount()).isEqualTo(21);
        var second = service.published(29L, new ListWindow(Long.parseLong(first.nextCursor()), 20));
        assertThat(second.items()).hasSize(20).noneMatch(row -> first.items().stream().anyMatch(old -> old.id().equals(row.id())));
        assertThat(service.summary(999L).publishedCount()).isZero();
        assertThat(service.summary(999L).averageRating()).isZero();
    }
}
