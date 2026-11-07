package org.booklore.model.dto;

import org.booklore.model.entity.BookdropFileEntity.Status;
import lombok.Data;

@Data
public class BookdropFile {
    private Long id;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private BookMetadata originalMetadata;
    private BookMetadata fetchedMetadata;
    private boolean metadataMatchRejected;
    private String metadataMatchMessage;
    private String createdAt;
    private String updatedAt;
    private Status status;
}
