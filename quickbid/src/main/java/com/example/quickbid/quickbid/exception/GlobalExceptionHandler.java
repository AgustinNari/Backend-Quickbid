package com.example.quickbid.quickbid.exception;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.example.quickbid.quickbid.dto.response.ApiError;
import com.example.quickbid.quickbid.dto.response.ApiResponse;
import com.example.quickbid.quickbid.service.MailDeliveryException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
		return ResponseEntity.status(exception.getStatus())
				.body(ApiResponse.failure(exception.getMessage(), exception.getErrors()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException exception) {
		List<ApiError> errors = exception.getBindingResult().getFieldErrors().stream()
				.map(error -> new ApiError(error.getField(), "INVALID_FIELD", error.getDefaultMessage()))
				.toList();
		return ResponseEntity.badRequest().body(ApiResponse.failure("La solicitud contiene datos inválidos", errors));
	}

	@ExceptionHandler(MissingServletRequestPartException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException exception) {
		ApiError error = new ApiError(exception.getRequestPartName(), "MISSING_FILE", "El archivo es obligatorio");
		return ResponseEntity.badRequest().body(ApiResponse.failure("Falta documentación requerida", List.of(error)));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException exception) {
		ApiError error = new ApiError(exception.getParameterName(), "MISSING_PARAMETER", "El parámetro es obligatorio");
		return ResponseEntity.badRequest().body(ApiResponse.failure("Faltan datos requeridos", List.of(error)));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
		ApiError error = new ApiError(exception.getName(), "INVALID_PARAMETER", "El parámetro tiene formato inválido");
		return ResponseEntity.badRequest().body(ApiResponse.failure("La solicitud contiene datos inválidos", List.of(error)));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
		ApiError error = new ApiError(null, "INVALID_JSON", "El cuerpo JSON tiene formato inválido");
		return ResponseEntity.badRequest().body(ApiResponse.failure("No se pudo interpretar la solicitud", List.of(error)));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException exception) {
		ApiError error = new ApiError(null, "RESOURCE_NOT_FOUND", "El recurso solicitado no existe");
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ApiResponse.failure("Recurso inexistente", List.of(error)));
	}

	@ExceptionHandler(MailDeliveryException.class)
	public ResponseEntity<ApiResponse<Void>> handleMailDeliveryException(MailDeliveryException exception) {
		ApiError error = new ApiError(null, "MAIL_DELIVERY_FAILED", "No se pudo enviar el correo");
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(ApiResponse.failure("El proveedor de correo no está disponible", List.of(error)));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
		LOGGER.error("Unexpected server error", exception);
		ApiError error = new ApiError(null, "INTERNAL_SERVER_ERROR", "Ocurrió un error interno");
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiResponse.failure("No se pudo procesar la solicitud", List.of(error)));
	}
}
