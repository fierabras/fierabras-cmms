package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.integration.support.TestWorkOrders;
import com.grash.integration.support.TestWorkOrders.Fixture;
import com.grash.model.Asset;
import com.grash.model.enums.Status;
import com.grash.repository.FileRepository;
import com.grash.repository.PartRepository;
import com.grash.repository.WorkOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-17 characterization: fixes how {@code DELETE /files/{id}} and the removal of a task with an image behave before
 * any change. The 403 and 404 answers must keep passing after P-17.
 */
@AutoConfigureMockMvc
class FileDeletionCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private static final String DELETED_BODY = "{\"success\":true,\"message\":\"Deleted successfully\"}";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final TestWorkOrders workOrders;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    FileDeletionCharacterizationIntegrationTest(TestOrganizations organizations, TestRecords records,
                                                MockMvc mockMvc, ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                WorkOrderRepository workOrderRepository,
                                                PartRepository partRepository, FileRepository fileRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.workOrders = new TestWorkOrders(api, objectMapper, records, workOrderRepository, partRepository,
                fileRepository);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @AfterEach
    void cleanUp() {
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData data() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    /**
     * Uploads one file through the API, as the web app does, optionally as the image of a task; returns its id.
     */
    private Long upload(String token, Long taskId) {
        MockMultipartFile file = new MockMultipartFile("files", TestRecords.unique("p17") + ".txt",
                MediaType.TEXT_PLAIN_VALUE, "P-17".getBytes(StandardCharsets.UTF_8));
        MockMultipartHttpServletRequestBuilder request = multipart("/files/upload").file(file);
        request.param("folder", "p17").param("hidden", "false").param("type", "IMAGE");
        if (taskId != null) request.param("taskId", taskId.toString());
        Response response = api.call(request, token);
        assertTrue(response.is2xx(), "upload: " + response);
        try {
            return objectMapper.readTree(response.body()).get(0).get("id").asLong();
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    private String path(Long fileId) {
        return jdbc.queryForObject("SELECT path FROM file WHERE id = ?", String.class, fileId);
    }

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private long files(Long id) {
        return count("SELECT count(*) FROM file WHERE id = ?", id);
    }

    private long auditRecords(CommonData data) {
        return count("SELECT count(*) FROM audit_log WHERE company_id = ?", data.o1().company().getId());
    }

    private Response deleteFile(Long id, String token) {
        return api.call(delete("/files/" + id), token);
    }

    @Nested
    class InUse {

        @Test
        void imageOfAnAsset_200_assetLosesItsImage_objectKept_noRecord() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Asset asset = records.asset(data.o1().company());
            Long file = upload(admin, null);
            String path = path(file);
            jdbc.update("UPDATE asset SET image_id = ? WHERE id = ?", file, asset.getId());

            Response response = deleteFile(file, admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(DELETED_BODY, response.body()),
                    () -> assertEquals(0, files(file)),
                    () -> assertNull(jdbc.queryForObject("SELECT image_id FROM asset WHERE id = ?", Long.class,
                            asset.getId()), "SET NULL today"),
                    () -> assertTrue(storageDouble.contains(path), "the object stays in the storage"),
                    () -> assertTrue(storageDouble.calls().stream().allMatch(call -> List.of("upload",
                            "generateSignedUrl").contains(call.name())), "only the upload: " + storageDouble.calls()),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void audioOfARequest_200_requestDeletedInCascade() {
            // C-04: request.audio_description_id is CASCADE today
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Response created = api.call(post("/requests").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"" + TestRecords.unique("P-17 request") + "\"}"), admin);
            assertTrue(created.is2xx(), created.toString());
            Long request;
            try {
                request = objectMapper.readTree(created.body()).get("id").asLong();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            Long file = upload(admin, null);
            jdbc.update("UPDATE request SET audio_description_id = ? WHERE id = ?", file, request);

            Response response = deleteFile(file, admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, files(file)),
                    () -> assertEquals(0, count("SELECT count(*) FROM request WHERE id = ?", request),
                            "the request is deleted with its audio today"),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void attachedToAClosedWorkOrder_200_associationRemoved() {
            // H1-02
            CommonData data = data();
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.COMPLETE, false);

            Response response = deleteFile(f.file(), api.login(data.o1().admin()));

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, files(f.file())),
                    () -> assertEquals(0, count("SELECT count(*) FROM work_order_files WHERE work_order_id = ?",
                            f.id()), "the closed work order loses the attachment today"),
                    () -> assertEquals(0, auditRecords(data)));
        }
    }

    @Nested
    class Unused {

        @Test
        void unused_200_deleted_objectKept_noRecord() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Long file = upload(admin, null);
            String path = path(file);

            Response response = deleteFile(file, admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(DELETED_BODY, response.body()),
                    () -> assertEquals(0, files(file)),
                    () -> assertTrue(storageDouble.contains(path), "the object stays in the storage"),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void byItsCreatorWithoutDeleteOther_200() {
            CommonData data = data();
            String technician = api.login(data.o1().technician());
            Long file = upload(technician, null);

            Response response = deleteFile(file, technician);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, files(file)));
        }
    }

    @Nested
    class Rejected {

        @Test
        void technicianOnAFileOfAnotherUser_403_fileKept() {
            CommonData data = data();
            Long file = upload(api.login(data.o1().admin()), null);

            Response response = deleteFile(file, api.login(data.o1().technician()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertEquals(1, files(file)),
                    () -> assertEquals(0, auditRecords(data)));
        }

        @Test
        void adminOfAnotherOrganization_403_fileKept() {
            CommonData data = data();
            Long file = upload(api.login(data.o1().admin()), null);

            Response response = deleteFile(file, api.login(data.o2().admin()));

            assertAll(
                    () -> assertEquals(403, response.status(), response.toString()),
                    () -> assertEquals(1, files(file)));
        }

        @Test
        void unknownFile_404() {
            CommonData data = data();

            Response response = deleteFile(Long.MAX_VALUE, api.login(data.o1().admin()));

            assertEquals(404, response.status(), response.toString());
        }
    }

    @Nested
    class TaskImages {

        @Test
        void imageOfATaskOfAnOpenWorkOrder_200_deleted_objectKept() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            Long file = upload(admin, f.task());
            String path = path(file);

            Response response = deleteFile(file, admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, files(file)),
                    () -> assertTrue(storageDouble.contains(path), "the object stays in the storage"));
        }

        @Test
        void imageOfATaskOfAClosedWorkOrder_200_deleted() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            Long file = upload(admin, f.task());
            workOrders.moveTo(f.id(), Status.COMPLETE, false);

            Response response = deleteFile(file, admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, files(file), "the closed work order loses the image of its task today"));
        }

        @Test
        void taskWithAnImageRemoved_200_fileRowGoesInCascade_objectKept() {
            CommonData data = data();
            String admin = api.login(data.o1().admin());
            Fixture f = workOrders.create(data.o1().admin(), data.o1().technician(), Status.OPEN, false);
            Long file = upload(admin, f.task());
            String path = path(file);

            Response response = api.call(delete("/tasks/" + f.task()), admin);

            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(0, count("SELECT count(*) FROM task WHERE id = ?", f.task())),
                    () -> assertEquals(0, files(file), "file.task_id is CASCADE"),
                    () -> assertTrue(storageDouble.contains(path), "the object stays in the storage"),
                    () -> assertEquals(List.of("TASK {\"file\": 1} 1"), jdbc.queryForList(
                            "SELECT entity_type || ' ' || cascade_rows::text || ' ' || storage_objects "
                                    + "FROM audit_log WHERE company_id = ?", String.class,
                            data.o1().company().getId())));
        }
    }
}
