package com.gonecase.filelocker.file;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.core.exception.SdkClientException;

@WebMvcTest(controllers = FileController.class, properties = "locker.api-key=test-key")
class FileControllerTest {

    private static final String ID = "3f2b1c4e-8a9d-4e7f-b6c5-1d2e3f4a5b6c";

    @Autowired MockMvc mvc;
    @MockBean FileService files;

    @Test
    void rejectsMissingApiKey() throws Exception {
        mvc.perform(get("/files")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongApiKey() throws Exception {
        mvc.perform(get("/files").header("X-API-Key", "nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsFiles() throws Exception {
        when(files.list()).thenReturn(List.of(new FileInfo(ID, "a.txt", 5, Instant.EPOCH, null)));
        mvc.perform(get("/files").header("X-API-Key", "test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID))
                .andExpect(jsonPath("$[0].name").value("a.txt"));
    }

    @Test
    void uploadReturnsCreated() throws Exception {
        when(files.upload(any())).thenReturn(new FileInfo(ID, "a.txt", 5, Instant.EPOCH, "abc"));
        mvc.perform(multipart("/files")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes()))
                        .header("X-API-Key", "test-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.sha256").value("abc"));
    }

    @Test
    void uploadWithoutFilePartIsBadRequest() throws Exception {
        mvc.perform(multipart("/files").header("X-API-Key", "test-key")).andExpect(status().isBadRequest());
    }

    @Test
    void returnsDownloadLink() throws Exception {
        when(files.downloadLink(ID)).thenReturn(new DownloadLink("https://example/signed", Instant.EPOCH));
        mvc.perform(get("/files/{id}/download-url", ID).header("X-API-Key", "test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://example/signed"));
    }

    @Test
    void mapsDomainErrorsToStatusCodes() throws Exception {
        when(files.downloadLink("bad")).thenThrow(new FileErrors.BadRequest("invalid file id"));
        doThrow(new FileErrors.NotFound(ID)).when(files).delete(ID);

        mvc.perform(get("/files/bad/download-url").header("X-API-Key", "test-key"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/files/{id}", ID).header("X-API-Key", "test-key"))
                .andExpect(status().isNotFound());
    }

    @Test
    void storageErrorsDoNotLeakDetails() throws Exception {
        when(files.list()).thenThrow(SdkClientException.create("AccessDenied for arn:aws:iam::123:role/x"));
        mvc.perform(get("/files").header("X-API-Key", "test-key"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(not(containsString("arn:aws"))));
    }
}
