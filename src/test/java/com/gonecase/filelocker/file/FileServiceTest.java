package com.gonecase.filelocker.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gonecase.filelocker.config.LockerProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class FileServiceTest {

    private final LockerProperties props =
            new LockerProperties("locker-bucket", "alias/file-locker", Duration.ofMinutes(5), "us-east-1", null, null);
    private S3Client s3;
    private FileService service;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        // Real presigner: signing is done locally, no network or real credentials needed
        S3Presigner presigner = S3Presigner.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATEST", "secret")))
                .build();
        service = new FileService(s3, presigner, props);
    }

    private static ListObjectsV2Response listing(boolean truncated, String... keys) {
        var b = ListObjectsV2Response.builder().isTruncated(truncated).nextContinuationToken(truncated ? "next" : null);
        b.contents(java.util.Arrays.stream(keys)
                .map(k -> S3Object.builder().key(k).size(5L).lastModified(Instant.EPOCH).build())
                .toList());
        return b.build();
    }

    @Test
    void uploadEncryptsWithKmsAndSanitizesName() throws Exception {
        var file = new MockMultipartFile("file", "../../etc/my secret.txt", "text/plain", "hello".getBytes());

        FileInfo info = service.upload(file);

        var captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest put = captor.getValue();
        assertThat(put.bucket()).isEqualTo("locker-bucket");
        assertThat(put.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(put.ssekmsKeyId()).isEqualTo("alias/file-locker");
        assertThat(put.key()).isEqualTo(info.id() + "/my_secret.txt");
        assertThat(UUID.fromString(info.id())).isNotNull();
        assertThat(info.size()).isEqualTo(5);
        assertThat(info.sha256()).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(put.metadata()).containsEntry("sha256", info.sha256());
    }

    @Test
    void rejectsEmptyFile() {
        var empty = new MockMultipartFile("file", "a.txt", "text/plain", new byte[0]);
        assertThatThrownBy(() -> service.upload(empty)).isInstanceOf(FileErrors.BadRequest.class);
        verifyNoInteractions(s3);
    }

    @Test
    void sanitizeHandlesHostileNames() {
        assertThat(FileService.sanitize("..\\..\\windows\\evil.exe")).isEqualTo("evil.exe");
        assertThat(FileService.sanitize("a/b/../c.pdf")).isEqualTo("c.pdf");
        assertThat(FileService.sanitize("..")).isEqualTo("file");
        assertThat(FileService.sanitize(null)).isEqualTo("file");
        assertThat(FileService.sanitize("rëport (1).pdf")).isEqualTo("r_port__1_.pdf");
        assertThat(FileService.sanitize("x".repeat(300) + ".txt")).hasSize(100).endsWith(".txt");
    }

    @Test
    void downloadLinkIsPresignedAndShortLived() {
        String id = UUID.randomUUID().toString();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listing(false, id + "/report.pdf"));

        DownloadLink link = service.downloadLink(id);

        assertThat(link.url())
                .contains("locker-bucket")
                .contains(id + "/report.pdf")
                .contains("X-Amz-Signature=")
                .contains("X-Amz-Expires=300");
        assertThat(link.expiresAt()).isBetween(Instant.now().plusSeconds(290), Instant.now().plusSeconds(310));
    }

    @Test
    void invalidIdNeverReachesS3() {
        assertThatThrownBy(() -> service.downloadLink("../other-user/secret"))
                .isInstanceOf(FileErrors.BadRequest.class);
        assertThatThrownBy(() -> service.delete("")).isInstanceOf(FileErrors.BadRequest.class);
        verifyNoInteractions(s3);
    }

    @Test
    void unknownIdIsNotFound() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listing(false));
        assertThatThrownBy(() -> service.downloadLink(UUID.randomUUID().toString()))
                .isInstanceOf(FileErrors.NotFound.class);
    }

    @Test
    void listFollowsPagination() {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(listing(true, a + "/one.txt"))
                .thenReturn(listing(false, b + "/two.txt"));

        var files = service.list();

        assertThat(files).extracting(FileInfo::id).containsExactly(a, b);
        assertThat(files).extracting(FileInfo::name).containsExactly("one.txt", "two.txt");
    }

    @Test
    void deleteRemovesTheResolvedKey() {
        String id = UUID.randomUUID().toString();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listing(false, id + "/a.txt"));

        service.delete(id);

        var captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(captor.capture());
        assertThat(captor.getValue().key()).isEqualTo(id + "/a.txt");
    }
}
