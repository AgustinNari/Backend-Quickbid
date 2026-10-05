package com.example.quickbid.quickbid.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.quickbid.quickbid.audit.AuditEvent;
import com.example.quickbid.quickbid.audit.AuditService;
import com.example.quickbid.quickbid.dto.request.DireccionEnvioRequest;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.Address;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.BuyerMetrics;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.HistoryItem;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.MonthlyActivity;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.Notification;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.Page;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.Permissions;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.Profile;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.SellerMetrics;
import com.example.quickbid.quickbid.dto.response.UsuarioDtos.Statistics;
import com.example.quickbid.quickbid.entity.app.CuentaApp;
import com.example.quickbid.quickbid.entity.app.DireccionEnvio;
import com.example.quickbid.quickbid.entity.app.NotificacionApp;
import com.example.quickbid.quickbid.exception.BusinessException;
import com.example.quickbid.quickbid.repository.app.CuentaAppRepository;
import com.example.quickbid.quickbid.repository.app.DireccionEnvioRepository;
import com.example.quickbid.quickbid.repository.app.NotificacionAppRepository;
import com.example.quickbid.quickbid.repository.app.UsuarioQueryRepository;
import com.example.quickbid.quickbid.repository.legacy.PersonaRepository;

@Service
public class UsuarioService {
	private static final int MAX_ACTIVE_ADDRESSES = 5;

	private final CuentaAppRepository cuentas;
	private final PersonaRepository personas;
	private final CategoriaService categorias;
	private final NotificacionAppRepository notificaciones;
	private final DireccionEnvioRepository direcciones;
	private final UsuarioQueryRepository queries;
	private final AuditService audit;

	public UsuarioService(CuentaAppRepository cuentas, PersonaRepository personas, CategoriaService categorias,
			NotificacionAppRepository notificaciones, DireccionEnvioRepository direcciones,
			UsuarioQueryRepository queries, AuditService audit) {
		this.cuentas = cuentas;
		this.personas = personas;
		this.categorias = categorias;
		this.notificaciones = notificaciones;
		this.direcciones = direcciones;
		this.queries = queries;
		this.audit = audit;
	}

	@Transactional
	public Profile profile(Long id) {
		var cuenta = account(id);
		var persona = personas.findById(cuenta.getPersonaId()).orElseThrow();
		var progreso = categorias.synchronize(cuenta);
		String[] name = persona.getNombre().trim().split("\\s+", 2);
		boolean multa = cuenta.getEstado().equals("restriccion_multa");
		return new Profile(cuenta.getId(), name[0], name.length > 1 ? name[1] : "", cuenta.getEmail(),
				cuenta.getCategoriaCalculada(), cuenta.getPuntos(), progreso, cuenta.getEstado(),
				multa ? "restringida_por_multa" : "habilitada",
				new Permissions(true, !multa, !multa, true, multa));
	}

	@Transactional(readOnly = true)
	public Statistics statistics(Long id, String period) {
		account(id);
		String normalizedPeriod = normalizePeriod(period);
		OffsetDateTime since = periodStart(normalizedPeriod);
		var snapshot = queries.statistics(id, since);
		int bids = snapshot.bids(), wins = snapshot.wins();
		BigDecimal rate = bids == 0 ? BigDecimal.ZERO
				: BigDecimal.valueOf(wins * 100.0 / bids).setScale(2, RoundingMode.HALF_UP);
		BuyerMetrics buyer = new BuyerMetrics(snapshot.totalBid(), snapshot.totalPaid(), rate, snapshot.purchases(),
				bids, snapshot.auctions());
		SellerMetrics seller = new SellerMetrics(snapshot.consignments(), snapshot.soldConsignments(),
				snapshot.liquidatedConsignments(), snapshot.totalLiquidated());
		return new Statistics(normalizedPeriod, snapshot.totalBid(), snapshot.totalPaid(), rate, snapshot.purchases(),
				bids, snapshot.auctions(), buyer, seller, monthly(id, since));
	}

	@Transactional(readOnly = true)
	public Page<HistoryItem> history(Long id, int page, int size) {
		account(id);
		checkPage(page, size);
		return page(queries.findHistory(id, page, size), page, size, queries.countHistory(id));
	}

	@Transactional(readOnly = true)
	public Page<Notification> notifications(Long id, String tipo, Boolean leida, int page, int size) {
		account(id);
		checkPage(page, size);
		return page(queries.findNotifications(id, tipo, leida, page, size), page, size,
				queries.countNotifications(id, tipo, leida));
	}

	@Transactional
	public Notification readNotification(Long cuentaId, Long id) {
		account(cuentaId);
		var notification = notificaciones.findByIdAndCuentaId(id, cuentaId)
				.orElseThrow(() -> notificaciones.existsById(id) ? forbidden() : notFound("Notificacion inexistente"));
		notification.markRead();
		audit.record(new AuditEvent("usuario", cuentaId, "usuario.notificacion_leida", "notificacion", id, "{}"));
		return notification(notification);
	}

	@Transactional
	public List<Notification> readAllNotifications(Long cuentaId) {
		account(cuentaId);
		var unread = notificaciones.findAllByCuentaIdAndLeidaFalseOrderByCreatedAtDesc(cuentaId);
		unread.forEach(NotificacionApp::markRead);
		if (!unread.isEmpty()) {
			audit.record(new AuditEvent("usuario", cuentaId, "usuario.notificaciones_leidas", "notificacion", null,
					"{\"cantidad\":" + unread.size() + "}"));
		}
		return unread.stream().map(this::notification).toList();
	}

	@Transactional(readOnly = true)
	public Address address(Long id) {
		account(id);
		return direcciones.findFirstByCuentaIdAndPrincipalTrueAndDeletedAtIsNull(id).map(this::address).orElse(null);
	}

	@Transactional(readOnly = true)
	public List<Address> addresses(Long id) {
		account(id);
		return direcciones.findAllByCuentaIdAndDeletedAtIsNullOrderByPrincipalDescCreatedAtDesc(id).stream()
				.map(this::address).toList();
	}

	@Transactional
	public Address updateAddress(Long id, DireccionEnvioRequest request) {
		account(id);
		direcciones.findFirstByCuentaIdAndPrincipalTrueAndDeletedAtIsNull(id).ifPresent(DireccionEnvio::delete);
		return createAddress(id, request);
	}

	@Transactional
	public Address createAddress(Long id, DireccionEnvioRequest request) {
		account(id);
		if (direcciones.countByCuentaIdAndDeletedAtIsNull(id) >= MAX_ACTIVE_ADDRESSES) {
			throw unprocessable("Máximo de direcciones activas alcanzado", "ADDRESS_LIMIT_EXCEEDED");
		}
		boolean first = direcciones.countByCuentaIdAndDeletedAtIsNull(id) == 0;
		DireccionEnvio address = new DireccionEnvio(id);
		address.update(request.alias(), request.destinatario(), request.calle(), request.numero(), request.piso(),
				request.codigoPostal(), request.localidad(), request.provincia(), request.pais(), request.telefono());
		if (first) address.makePrincipal(); else address.clearPrincipal();
		address = direcciones.save(address);
		audit.record(new AuditEvent("usuario", id, "usuario.direccion_envio_creada", "direccion_envio",
				address.getId(), "{}"));
		return address(address);
	}

	@Transactional
	public void deleteAddress(Long accountId, Long addressId) {
		account(accountId);
		DireccionEnvio address = ownedAddress(accountId, addressId);
		boolean wasPrincipal = Boolean.TRUE.equals(address.getPrincipal());
		address.delete();
		audit.record(new AuditEvent("usuario", accountId, "usuario.direccion_envio_eliminada", "direccion_envio",
				addressId, "{}"));
		if (wasPrincipal) {
			direcciones.findAllByCuentaIdAndDeletedAtIsNullOrderByPrincipalDescCreatedAtDesc(accountId).stream()
					.findFirst().ifPresent(DireccionEnvio::makePrincipal);
		}
	}

	@Transactional
	public Address setPrincipalAddress(Long accountId, Long addressId) {
		account(accountId);
		DireccionEnvio selected = ownedAddress(accountId, addressId);
		direcciones.clearOtherActivePrincipals(accountId, addressId);
		selected.makePrincipal();
		direcciones.saveAndFlush(selected);
		audit.record(new AuditEvent("usuario", accountId, "usuario.direccion_envio_principal", "direccion_envio",
				addressId, "{}"));
		return address(selected);
	}

	private DireccionEnvio ownedAddress(Long accountId, Long addressId) {
		return direcciones.findByIdAndCuentaIdAndDeletedAtIsNull(addressId, accountId)
				.orElseThrow(() -> direcciones.existsById(addressId) ? forbidden() : notFound("Direccion inexistente"));
	}

	private CuentaApp account(Long id) {
		return cuentas.findById(id).orElseThrow(() -> notFound("Cuenta inexistente"));
	}

	private String normalizePeriod(String period) {
		String value = period == null || period.isBlank() ? "total" : period.trim().toLowerCase();
		if (!Set.of("mes", "trimestre", "anual", "total").contains(value)) {
			throw new BusinessException(HttpStatus.BAD_REQUEST, "Periodo invalido", "INVALID_PERIOD");
		}
		return value;
	}

	private OffsetDateTime periodStart(String period) {
		LocalDate today = LocalDate.now();
		return switch (period) {
			case "mes" -> today.withDayOfMonth(1).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
			case "trimestre" -> {
				int firstMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
				yield LocalDate.of(today.getYear(), firstMonth, 1).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
			}
			case "anual" -> LocalDate.of(today.getYear(), 1, 1).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
			default -> null;
		};
	}

	private List<MonthlyActivity> monthly(Long id, OffsetDateTime since) {
		Map<YearMonth, int[]> months = new TreeMap<>();
		queries.findMonthlyActivity(id, since).forEach(activity -> {
			YearMonth month = YearMonth.from(activity.createdAt());
			int[] count = months.computeIfAbsent(month, key -> new int[2]);
			if (activity.type().equals("puja")) count[0]++; else count[1]++;
		});
		return months.entrySet().stream()
				.map(entry -> new MonthlyActivity(entry.getKey().toString(), entry.getValue()[0], entry.getValue()[1]))
				.toList();
	}

	private Notification notification(NotificacionApp notification) {
		return new Notification(notification.getId(), notification.getTipo(), notification.getTitulo(),
				notification.getDescripcion(), notification.getReferenciaTipo(), notification.getReferenciaId(),
				notification.getLeida(), notification.getCreatedAt());
	}

	private Address address(DireccionEnvio address) {
		return new Address(address.getId(), address.getAlias(), address.getDestinatario(), address.getCalle(),
				address.getNumero(), address.getPiso(), address.getCodigoPostal(), address.getLocalidad(),
				address.getProvincia(), address.getPais(), address.getTelefono(), address.getPrincipal());
	}

	private <T> Page<T> page(List<T> content, int page, int size, long total) {
		return new Page<>(content, page, size, total, (int) Math.ceil((double) total / size));
	}

	private void checkPage(int page, int size) {
		if (page < 0 || size < 1 || size > 100) {
			throw new BusinessException(HttpStatus.BAD_REQUEST, "Paginación inválida", "INVALID_PAGE");
		}
	}

	private BusinessException forbidden() {
		return new BusinessException(HttpStatus.FORBIDDEN, "El recurso no pertenece al usuario", "RESOURCE_NOT_OWNED");
	}

	private BusinessException unprocessable(String message, String code) {
		return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, message, code);
	}

	private BusinessException notFound(String message) {
		return new BusinessException(HttpStatus.NOT_FOUND, message, "RESOURCE_NOT_FOUND");
	}
}
