package com.flexcity.configmanager.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flexcity.configmanager.model.Machine;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * machines.json dosyasını okur ve önbellekte tutar.
 *
 * Öncelik sırası:
 *   1. JAR'ın çalıştığı dizindeki machines.json  (dış config)
 *   2. classpath:/machines.json                   (kaynak içinde)
 */
@Service
public class MachineService {

    private static final Logger log = LoggerFactory.getLogger(MachineService.class);

    @Value("${app.machines-file:machines.json}")
    private String machinesFile;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private List<Machine> machines = Collections.emptyList();

    @PostConstruct
    public void load() {
        try {
            File external = new File(machinesFile);
            if (external.exists()) {
                machines = objectMapper.readValue(external, new TypeReference<>() {});
                log.info("machines.json yüklendi (harici): {} makine", machines.size());
            } else {
                ClassPathResource cpr = new ClassPathResource("machines.json");
                try (InputStream is = cpr.getInputStream()) {
                    machines = objectMapper.readValue(is, new TypeReference<>() {});
                    log.info("machines.json yüklendi (classpath): {} makine", machines.size());
                }
            }
        } catch (Exception e) {
            log.error("machines.json okunamadı: {}", e.getMessage());
            machines = Collections.emptyList();
        }
    }

    public List<Machine> getAll() {
        return machines;
    }

    public Optional<Machine> findById(String id) {
        return machines.stream().filter(m -> m.getId().equals(id)).findFirst();
    }
}
