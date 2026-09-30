package smartticketing.exception;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new ApiError(LocalDateTime.now(),
                e.getStatusCode().value(), e.getReason() == null ? "요청을 처리할 수 없습니다." : e.getReason()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> parameter(Exception e) {
        return ResponseEntity.badRequest().body(new ApiError(LocalDateTime.now(), 400, "필수 요청 값이나 형식을 확인해주세요."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        String m = e.getBindingResult().getFieldErrors().stream().findFirst().map(x -> x.getDefaultMessage()).orElse("잘못된 요청입니다.");
        return ResponseEntity.badRequest().body(new ApiError(LocalDateTime.now(), 400, m));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ApiError(LocalDateTime.now(), 400, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> state(IllegalStateException e) {
        return ResponseEntity.status(409).body(new ApiError(LocalDateTime.now(), 409, e.getMessage()));
    }
}
