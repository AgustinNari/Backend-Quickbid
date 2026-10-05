package com.example.quickbid.quickbid.service;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoAssetSeedService implements ApplicationRunner {
	private static final Logger log = LoggerFactory.getLogger(DemoAssetSeedService.class);
	private static final ProductAssets[] PRODUCTS = {
			new ProductAssets(8010, 16110, "Lampara italiana restaurada", "lampara_italiana_restaurada"),
			new ProductAssets(8011, 16111, "Sillon escandinavo de roble", "sillon_escandinavo_roble"),
			new ProductAssets(8012, 16112, "Juego de vajilla art deco", "vajilla_art_deco"),
			new ProductAssets(8013, 16113, "Reloj de mesa coleccionable", "reloj_mesa_coleccionable")
	};
	private static final int EXPECTED_IMAGES = 6;
	private static final long DEMO_CONSIGNOR_ACCOUNT_ID = 3004L;

	private final JdbcTemplate jdbc;
	private final ResourcePatternResolver resources;
	private final boolean enabled;
	private final boolean strict;
	private final String productAssetsBasePath;

	public DemoAssetSeedService(JdbcTemplate jdbc, ResourcePatternResolver resources,
			@Value("${app.demo.assets.enabled:false}") boolean enabled,
			@Value("${app.demo.assets.strict:false}") boolean strict,
			@Value("${app.demo.assets.base-path:classpath:/demo-assets/products}") String productAssetsBasePath) {
		this.jdbc = jdbc;
		this.resources = resources;
		this.enabled = enabled;
		this.strict = strict;
		this.productAssetsBasePath = productAssetsBasePath;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!enabled) return;
		seedDemoAssets();
	}

	@Transactional
	public void seedDemoAssets() {
		List<String> errors = new ArrayList<>();
		for (ProductAssets product : PRODUCTS) {
			try {
				List<Asset> assets = assets(product);
				if (assets.size() < EXPECTED_IMAGES) {
					String message = "Producto demo " + product.id() + " tiene " + assets.size()
							+ " imagenes; se esperaban " + EXPECTED_IMAGES;
					if (strict) {
						errors.add(message);
						continue;
					}
					log.warn(message);
					if (assets.isEmpty()) {
						log.warn("Producto demo {} conserva sus imagenes actuales porque no hay assets validos",
								product.id());
						continue;
					}
				}
				validateDescriptiveNames(product, assets.stream().limit(EXPECTED_IMAGES).toList());
				replaceProductAssets(product.id(), product.consignmentId(),
						assets.stream().limit(EXPECTED_IMAGES).toList());
			} catch (Exception exception) {
				String message = "No se pudieron cargar assets demo para producto " + product.id() + ": "
						+ exception.getMessage();
				if (strict) errors.add(message);
				else log.warn(message, exception);
			}
		}
		if (!errors.isEmpty()) {
			throw new IllegalStateException("Seed de assets demo incompleto: " + String.join("; ", errors));
		}
		updateSequences();
	}

	private List<Asset> assets(ProductAssets product) throws Exception {
		String normalizedBase = productAssetsBasePath.replaceAll("/+$", "");
		Resource[] found = resources.getResources(normalizedBase + "/" + product.id() + "/*");
		List<Asset> assets = new ArrayList<>();
		for (Resource resource : found) {
			String filename = resource.getFilename();
			if (filename == null || filename.startsWith(".")) continue;
			byte[] bytes = resource.getInputStream().readAllBytes();
			if (!ImageContentTypeDetector.isSupported(filename, bytes)) {
				log.debug("Asset demo ignorado por formato no soportado: {}/{}", product.id(), filename);
				continue;
			}
			String contentType = ImageContentTypeDetector.detect(filename, bytes);
			assets.add(new Asset(filename, contentType, bytes, checksum(bytes)));
		}
		assets.sort(Comparator
				.comparingInt((Asset asset) -> slotOrder(product, asset.filename()))
				.thenComparingInt(asset -> descriptivePriority(product, asset.filename()))
				.thenComparing(Asset::filename));
		return orderedAssets(product, assets);
	}

	private List<Asset> orderedAssets(ProductAssets product, List<Asset> assets) {
		List<Asset> ordered = new ArrayList<>();
		Set<String> used = new HashSet<>();
		for (int order = 1; order <= EXPECTED_IMAGES; order++) {
			final int slot = order;
			assets.stream()
					.filter(asset -> slot(product, asset.filename()) != null && slot(product, asset.filename()) == slot)
					.min(Comparator
							.comparingInt((Asset asset) -> descriptivePriority(product, asset.filename()))
							.thenComparing(Asset::filename))
					.ifPresent(asset -> {
						ordered.add(asset);
						used.add(asset.filename());
					});
		}
		assets.stream()
				.filter(asset -> !used.contains(asset.filename()))
				.filter(asset -> slot(product, asset.filename()) == null)
				.sorted(Comparator.comparing(Asset::filename))
				.forEach(ordered::add);
		return ordered;
	}

	private int slotOrder(ProductAssets product, String filename) {
		Integer slot = slot(product, filename);
		return slot == null ? EXPECTED_IMAGES + 1 : slot;
	}

	private int descriptivePriority(ProductAssets product, String filename) {
		return isExpectedDescriptiveName(product, filename) ? 0 : 1;
	}

	private Integer slot(ProductAssets product, String filename) {
		String lower = filename.toLowerCase();
		for (int order = 1; order <= EXPECTED_IMAGES; order++) {
			String suffix = "%02d.".formatted(order);
			if (lower.startsWith(product.filenamePrefix() + "_" + suffix) || lower.startsWith(suffix)) {
				return order;
			}
		}
		return null;
	}

	private boolean isExpectedDescriptiveName(ProductAssets product, String filename) {
		String lower = filename.toLowerCase();
		for (int order = 1; order <= EXPECTED_IMAGES; order++) {
			if (lower.startsWith(product.filenamePrefix() + "_" + "%02d.".formatted(order))) return true;
		}
		return false;
	}

	private void validateDescriptiveNames(ProductAssets product, List<Asset> selected) {
		List<String> selectedNames = selected.stream().map(asset -> asset.filename().toLowerCase()).toList();
		for (int order = 1; order <= EXPECTED_IMAGES; order++) {
			final int slotNumber = order;
			String expected = product.filenamePrefix() + "_" + "%02d".formatted(order);
			boolean hasDescriptive = selectedNames.stream().anyMatch(name -> name.startsWith(expected + "."));
			boolean hasSimple = selectedNames.stream().anyMatch(name -> name.startsWith("%02d.".formatted(slotNumber)));
			if (!hasDescriptive && hasSimple) {
				log.warn("Producto demo {} ({}) usa nombre legacy para imagen {}. Recomendado: {}.png",
						product.id(), product.title(), order, expected);
			}
		}
	}

	private void replaceProductAssets(int productId, long consignmentId, List<Asset> assets) {
		int photoBase = photoBase(productId);
		long fileBase = fileBase(productId);
		int oldPlaceholderId = 8110 + (productId - 8010);
		jdbc.update("""
				DELETE FROM fotos
				WHERE producto=? AND (identificador BETWEEN ? AND ? OR identificador=?)
				""", productId, photoBase + 1, photoBase + EXPECTED_IMAGES, oldPlaceholderId);
		jdbc.update("""
				DELETE FROM app_consignacion_fotos
				WHERE solicitud_id=? AND (orden BETWEEN 1 AND ? OR archivo_id BETWEEN ? AND ?)
				""", consignmentId, EXPECTED_IMAGES, fileBase + 1, fileBase + EXPECTED_IMAGES);
		jdbc.update("DELETE FROM app_archivos WHERE id BETWEEN ? AND ?", fileBase + 1, fileBase + EXPECTED_IMAGES);

		Long ownerAccountId = ownerAccountId(consignmentId);
		int order = 0;
		for (Asset asset : assets) {
			order++;
			int photoId = photoBase + order;
			long fileId = fileBase + order;
			jdbc.update("INSERT INTO fotos(identificador,producto,foto) VALUES (?,?,?)",
					photoId, productId, asset.bytes());
			if (ownerAccountId != null) {
				jdbc.update("""
						INSERT INTO app_archivos(id,owner_cuenta_id,tipo_contexto,filename_original,content_type,
							size_bytes,storage_path,checksum,content_bytes)
						VALUES (?,?,?,?,?,?,?,?,?)
						""", fileId, ownerAccountId, "consignacion", asset.filename(), asset.contentType(),
						asset.bytes().length, "classpath-demo/products/" + productId + "/" + asset.filename(),
						asset.checksum(), asset.bytes());
				jdbc.update("""
						INSERT INTO app_consignacion_fotos(id,solicitud_id,archivo_id,orden)
						VALUES (?,?,?,?)
						""", fileId, consignmentId, fileId, order);
			}
		}
	}

	private Long ownerAccountId(long consignmentId) {
		List<Long> values = jdbc.query("SELECT cuenta_id FROM app_solicitudes_consignacion WHERE id=?",
				(rs, row) -> rs.getLong(1), consignmentId);
		if (!values.isEmpty()) return values.get(0);
		if (count("SELECT COUNT(*) FROM app_cuentas WHERE id=?", DEMO_CONSIGNOR_ACCOUNT_ID) > 0) {
			return DEMO_CONSIGNOR_ACCOUNT_ID;
		}
		log.warn("No existe consignacion demo {} ni cuenta demo {}", consignmentId, DEMO_CONSIGNOR_ACCOUNT_ID);
		return null;
	}

	private int photoBase(int productId) {
		return 811000 + ((productId - 8010) * 100);
	}

	private long fileBase(int productId) {
		return 821000L + ((long) productId - 8010L) * 100L;
	}

	private int count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Integer.class, args);
	}

	private String checksum(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 no disponible", exception);
		}
	}

	private void updateSequences() {
		updateSequence("fotos", "identificador");
		updateSequence("app_archivos", "id");
		updateSequence("app_consignacion_fotos", "id");
	}

	private void updateSequence(String table, String column) {
		try {
			jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('" + table + "', '" + column + "'), "
					+ "(SELECT COALESCE(MAX(" + column + "), 1) FROM " + table + "), true)", Long.class);
		} catch (Exception exception) {
			log.debug("No se pudo actualizar secuencia {}.{} en esta base", table, column);
		}
	}

	private record ProductAssets(int id, long consignmentId, String title, String filenamePrefix) {
	}

	private record Asset(String filename, String contentType, byte[] bytes, String checksum) {
	}
}
