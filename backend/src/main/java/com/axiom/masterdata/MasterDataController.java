package com.axiom.masterdata;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/master-data")
public class MasterDataController {
    private final MasterDataService masters;

    public MasterDataController(MasterDataService masters) { this.masters = masters; }

    @GetMapping("/{master}/template")
    public ResponseEntity<byte[]> template(@PathVariable String master) {
        return file(masters.template(master));
    }

    @PostMapping(path = "/{master}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MasterDataService.ImportResult importCsv(@PathVariable String master,
                                                     @RequestParam("file") MultipartFile file) throws IOException {
        return masters.importCsv(master, file.getBytes());
    }

    @GetMapping("/{master}/export")
    public ResponseEntity<byte[]> export(@PathVariable String master,
                                          @RequestParam MasterDataService.ExportFormat format,
                                          @RequestParam(required = false) String search,
                                          @RequestParam(required = false) String filter) {
        return file(masters.export(master, format, search, filter));
    }

    @DeleteMapping("/{master}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String master, @PathVariable UUID id) {
        masters.softDelete(master, id);
    }

    private ResponseEntity<byte[]> file(MasterDataService.FilePayload file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }
}
