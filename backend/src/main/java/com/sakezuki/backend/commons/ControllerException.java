package com.sakezuki.backend.commons;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class ControllerException {
    // 입력 오류와 예기치 않은 서버 오류를 구분해 프론트가 실패 상태를 표시할 수 있게 한다.
    // 내부 예외 상세는 로그에 남기고 일반 오류 응답에는 노출하지 않는다.
    // 400
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> illegalArgumentException(IllegalArgumentException e){
        log.warn("잘못된 요청: {}",e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
    }
    // 401
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<String> badCredentialsException(BadCredentialsException e){
        log.warn("로그인 실패: 아이디나 비밀번호가 잘못되었습니다 : {}",e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("아이디나 비밀번호가 잘못되었습니다.");
    }
    // 404
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> noResourceFoundException(NoResourceFoundException e){
        log.warn("요청한 페이지를 찾을 수 없습니다: {}",e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("요청한 페이지를 찾을 수 없습니다.");
    }
    // 405
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> httpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e){
        log.warn("지원하지 않는 HTTP 메서드입니다: {}",e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body("잘못된 요청입니다.");
    }
    // 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> exception(Exception e){
        log.error("알수없는 오류 발생",e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("알 수 없는 오류가 발생했습니다.");
    }
}
