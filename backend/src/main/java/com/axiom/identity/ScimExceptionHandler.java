package com.axiom.identity;

import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Keeps every SCIM failure in the RFC 7644 error envelope and media type. */
@RestControllerAdvice(assignableTypes = ScimController.class)
public class ScimExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException e){return response(400,e.getMessage(),"invalidValue");}
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String,Object>> missing(NotFoundException e){return response(404,e.getMessage(),null);}
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String,Object>> conflict(ConflictException e){return response(409,e.getMessage(),"uniqueness");}
    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<Map<String,Object>> forbidden(ForbiddenException e){return response(403,e.getMessage(),null);}
    private ResponseEntity<Map<String,Object>> response(int status,String detail,String type){return ResponseEntity.status(status).header("Content-Type","application/scim+json").body(ScimUserService.error(status,detail,type));}
}
