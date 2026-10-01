package com.stelody.review.repository;

import com.stelody.review.domain.ReviewItem;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<ReviewItem, UUID> {}
