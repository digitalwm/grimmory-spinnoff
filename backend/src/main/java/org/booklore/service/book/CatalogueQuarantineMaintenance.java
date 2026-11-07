package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogueQuarantineMaintenance {
    private final CatalogueQuarantineService quarantine;
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {run();}
    @Scheduled(cron = "0 20 3 * * *")
    public void run() {
        try {quarantine.maintain();}
        catch (Exception e) {log.error("Catalogue quarantine maintenance failed; quarantined files remain preserved", e);}
    }
}
