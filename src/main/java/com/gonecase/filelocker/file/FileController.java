package com.gonecase.filelocker.file;

import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/files")
public class FileController {

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FileInfo upload(@RequestParam("file") MultipartFile file) throws IOException {
        return files.upload(file);
    }

    @GetMapping
    public List<FileInfo> list() {
        return files.list();
    }

    @GetMapping("/{id}/download-url")
    public DownloadLink downloadUrl(@PathVariable String id) {
        return files.downloadLink(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        files.delete(id);
    }
}
