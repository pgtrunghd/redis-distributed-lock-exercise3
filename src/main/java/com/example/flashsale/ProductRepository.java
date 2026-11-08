package com.example.flashsale;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductRepository {
    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Product(Long id, String name, int stock, long sold) {}
    public record Purchase(Long id, Long productId, int quantity) {}

    public Product create(String name, int stock) {
        Long id = jdbc.queryForObject(
                "INSERT INTO products(name, stock) VALUES (?, ?) RETURNING id",
                Long.class, name, stock);
        return find(id).orElseThrow();
    }

    public Optional<Product> find(Long id) {
        return jdbc.query("""
                SELECT p.id, p.name, p.stock,
                       COALESCE((SELECT SUM(quantity) FROM purchases WHERE product_id=p.id),0) AS sold
                FROM products p WHERE p.id=?
                """, (rs, row) -> new Product(rs.getLong("id"), rs.getString("name"),
                rs.getInt("stock"), rs.getLong("sold")), id).stream().findFirst();
    }

    public boolean deduct(Long id, int quantity) {
        // Defense in depth if a stale lock holder resumes after a long JVM pause.
        return jdbc.update("UPDATE products SET stock=stock-? WHERE id=? AND stock>=?",
                quantity, id, quantity) == 1;
    }

    public Purchase savePurchase(Long productId, int quantity) {
        Long id = jdbc.queryForObject(
                "INSERT INTO purchases(product_id, quantity) VALUES (?, ?) RETURNING id",
                Long.class, productId, quantity);
        return new Purchase(id, productId, quantity);
    }
}
