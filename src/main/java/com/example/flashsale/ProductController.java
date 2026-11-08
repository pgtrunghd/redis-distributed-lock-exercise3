package com.example.flashsale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductRepository repository;
    private final PurchaseService service;

    public ProductController(ProductRepository repository, PurchaseService service) {
        this.repository = repository;
        this.service = service;
    }

    public record CreateProduct(@NotBlank @Size(max=255) String name, @Min(0) int stock) {}
    public record BuyRequest(@Min(1) int quantity) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductRepository.Product create(@Valid @RequestBody CreateProduct request) {
        return repository.create(request.name(), request.stock());
    }

    @GetMapping("/{id}")
    public ProductRepository.Product get(@PathVariable Long id) {
        return repository.find(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Không tìm thấy sản phẩm"));
    }

    @PostMapping("/{id}/purchases")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductRepository.Purchase buy(@PathVariable Long id, @Valid @RequestBody BuyRequest request) {
        return service.buy(id, request.quantity());
    }
}
