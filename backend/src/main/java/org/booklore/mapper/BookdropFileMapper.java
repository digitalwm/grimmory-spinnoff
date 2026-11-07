package org.booklore.mapper;

import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.BookdropFile;
import org.booklore.model.entity.BookdropFileEntity;
import org.mapstruct.Mapper;
import org.mapstruct.AfterMapping;
import org.mapstruct.MappingTarget;
import org.booklore.service.metadata.MetadataIdentityMatcher;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Mapper(componentModel = "spring")
public abstract class BookdropFileMapper {

    @Autowired
    private ObjectMapper objectMapper;

    @Mapping(target = "originalMetadata", source = "originalMetadata", qualifiedByName = "jsonToBookMetadata")
    @Mapping(target = "fetchedMetadata", source = "fetchedMetadata", qualifiedByName = "jsonToBookMetadata")
    @Mapping(target = "metadataMatchRejected", ignore = true)
    @Mapping(target = "metadataMatchMessage", ignore = true)
    public abstract BookdropFile toDto(BookdropFileEntity entity);

    @AfterMapping
    protected void validateCachedMatch(BookdropFileEntity entity, @MappingTarget BookdropFile result) {
        if (entity.getFetchedMetadata() == null || entity.getFetchedMetadata().isBlank()) return;
        BookMetadata fetched = result.getFetchedMetadata();
        if (fetched != null && MetadataIdentityMatcher.matches(result.getOriginalMetadata(), fetched)) return;
        result.setMetadataMatchRejected(true);
        result.setMetadataMatchMessage(fetched == null
                ? "Stored fetched metadata could not be verified. Original file metadata will be used."
                : "Rejected fetched match: " + fetched.getTitle() + " by "
                        + String.join(", ", fetched.getAuthors() == null ? java.util.List.of() : fetched.getAuthors())
                        + ". The title or author does not match this file. Original file metadata will be used.");
        result.setFetchedMetadata(null);
    }

    @Named("jsonToBookMetadata")
    protected BookMetadata jsonToBookMetadata(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(json, BookMetadata.class);
        } catch (JacksonException e) {
            return null;
        }
    }
}