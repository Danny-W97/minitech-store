package org.demo.productservice.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    List<Product> findAllByOrderByIdAsc();

    // Rank IDs in the database, returning metadata only for selected products.
    // Product descriptions/JSON and other entities are not loaded for this query.
    @Query(value = """
            select positions.product_id as "productId", positions.catalog_index as "catalogIndex",
                   positions.catalog_size as "catalogSize", layout.revision as "layoutRevision"
            from (select id as product_id, row_number() over (order by id) - 1 as catalog_index,
                         count(*) over () as catalog_size from products) positions
            cross join catalog_layout layout
            where layout.id = 1 and positions.product_id in (:ids)
            """, nativeQuery = true)
    List<CatalogPosition> findCatalogPositions(@Param("ids") List<Long> ids);

    @Query(value = "select revision, product_count as \"productCount\" from catalog_layout where id = 1", nativeQuery = true)
    CatalogState findCatalogState();

    @Query(value = "select id from products order by id limit :limit", nativeQuery = true)
    List<Long> findCatalogIds(@Param("limit") int limit);

    interface CatalogState {
        long getRevision();
        long getProductCount();
    }

    interface CatalogPosition {
        Long getProductId();
        long getCatalogIndex();
        long getCatalogSize();
        long getLayoutRevision();
    }
}
