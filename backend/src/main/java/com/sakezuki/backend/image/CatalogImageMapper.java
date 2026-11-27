package com.sakezuki.backend.image;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
interface CatalogImageMapper {
    record ManagedImage(long sakeNo, String imageUrl) {}

    List<ManagedImage> managedImages(@Param("numbers") List<Long> numbers);
}
