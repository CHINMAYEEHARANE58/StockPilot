package com.stockpilot;

import jakarta.persistence.*;

@Entity @Table(name="products")
public class Product {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false) public String name;
    @Column(name="category_id") public Long categoryId;
    @Column(nullable=false) public String unit="EACH";
    @Column(nullable=false) public boolean active=true;
    @Column(name="reorder_threshold",nullable=false) public long reorderThreshold=0;
    protected Product(){}
    Product(String name,Long categoryId,String unit,long threshold){this.name=name;this.categoryId=categoryId;this.unit=unit;this.reorderThreshold=threshold;}
}
