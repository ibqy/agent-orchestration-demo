package com.xb.agent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("全局异常处理 & 统一响应")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Nested
    @DisplayName("ApiResponse 统一响应")
    class ApiResponseTest {

        @Test
        @DisplayName("ok() 返回 code=0 和 data")
        void ok_returnsCodeZeroAndData() {
            ApiResponse<String> resp = ApiResponse.ok("hello");
            assertThat(resp.code()).isZero();
            assertThat(resp.message()).isEqualTo("ok");
            assertThat(resp.data()).isEqualTo("hello");
        }

        @Test
        @DisplayName("error() 返回指定 code 和 message，data 为 null")
        void error_returnsCodeAndMessage() {
            ApiResponse<Void> resp = ApiResponse.error(400, "bad request");
            assertThat(resp.code()).isEqualTo(400);
            assertThat(resp.message()).isEqualTo("bad request");
            assertThat(resp.data()).isNull();
        }
    }

    @Nested
    @DisplayName("异常处理")
    class ExceptionHandlingTest {

        @Test
        @DisplayName("ResponseStatusException → 对应 HTTP 状态码")
        void handleResponseStatus_mapsStatusCode() {
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
            ResponseEntity<ApiResponse<Void>> resp = handler.handleResponseStatus(ex);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().code()).isEqualTo(404);
            assertThat(resp.getBody().message()).isEqualTo("任务不存在");
        }

        @Test
        @DisplayName("IllegalArgumentException → 400")
        void handleIllegalArgument_returns400() {
            IllegalArgumentException ex = new IllegalArgumentException("goal 不能为空");
            ResponseEntity<ApiResponse<Void>> resp = handler.handleIllegalArgument(ex);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().code()).isEqualTo(400);
            assertThat(resp.getBody().message()).isEqualTo("goal 不能为空");
        }

        @Test
        @DisplayName("未知异常 → 500 + requestId")
        void handleUnknown_returns500WithRequestId() {
            Exception ex = new RuntimeException("boom");
            ResponseEntity<ApiResponse<Void>> resp = handler.handleUnknown(ex);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().code()).isEqualTo(500);
            assertThat(resp.getBody().message()).startsWith("系统异常，requestId=");
        }
    }
}
