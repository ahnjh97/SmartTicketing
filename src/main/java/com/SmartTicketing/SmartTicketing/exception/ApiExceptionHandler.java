package com.SmartTicketing.SmartTicketing.exception;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException e){String m=e.getBindingResult().getFieldErrors().stream().findFirst().map(x->x.getDefaultMessage()).orElse("잘못된 요청입니다.");return ResponseEntity.badRequest().body(new ApiError(LocalDateTime.now(),400,m));}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ApiError> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(new ApiError(LocalDateTime.now(),400,e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<ApiError> state(IllegalStateException e){return ResponseEntity.status(409).body(new ApiError(LocalDateTime.now(),409,e.getMessage()));}
}
