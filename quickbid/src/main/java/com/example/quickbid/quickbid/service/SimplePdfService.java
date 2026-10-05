package com.example.quickbid.quickbid.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class SimplePdfService {
	private static final java.nio.charset.Charset PDF_CHARSET = StandardCharsets.ISO_8859_1;
	private static final int MAX_LINE_LENGTH = 82;
	private static final int LINES_PER_PAGE = 32;

	public byte[] generate(String title, List<String> lines) {
		List<String> text = wrap(lines);
		List<List<String>> pages = new ArrayList<>();
		for (int start = 0; start < text.size(); start += LINES_PER_PAGE) {
			pages.add(text.subList(start, Math.min(start + LINES_PER_PAGE, text.size())));
		}
		if (pages.isEmpty()) pages.add(List.of());

		int pageCount = pages.size();
		int fontObject = 3 + pageCount;
		List<byte[]> objects = new ArrayList<>();
		objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
		StringBuilder kids = new StringBuilder();
		for (int page = 0; page < pageCount; page++) kids.append(3 + page).append(" 0 R ");
		objects.add(bytes("<< /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >>"));
		for (int page = 0; page < pageCount; page++) {
			int contentObject = fontObject + 1 + page;
			objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 "
					+ fontObject + " 0 R >> >> /Contents " + contentObject + " 0 R >>"));
		}
		objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"));
		for (List<String> page : pages) {
			StringBuilder stream = new StringBuilder("BT\n/F1 12 Tf\n72 760 Td\n");
			stream.append('(').append(escape(title)).append(") Tj\n");
			for (String line : page) stream.append("0 -20 Td\n(").append(escape(line)).append(") Tj\n");
			stream.append("ET\n");
			byte[] content = stream.toString().getBytes(PDF_CHARSET);
			objects.add(bytes("<< /Length " + content.length + " >>\nstream\n"
					+ new String(content, PDF_CHARSET) + "endstream"));
		}

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		write(output, "%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
		List<Integer> offsets = new ArrayList<>();
		for (int index = 0; index < objects.size(); index++) {
			offsets.add(output.size());
			write(output, (index + 1) + " 0 obj\n");
			output.writeBytes(objects.get(index));
			write(output, "\nendobj\n");
		}
		int xrefOffset = output.size();
		write(output, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
		for (Integer offset : offsets) write(output, String.format("%010d 00000 n \n", offset));
		write(output, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n"
				+ xrefOffset + "\n%%EOF\n");
		return output.toByteArray();
	}

	private List<String> wrap(List<String> lines) {
		List<String> wrapped = new ArrayList<>();
		for (String value : lines) {
			String[] paragraphs = (value == null ? "" : value).split("\\R", -1);
			for (String paragraph : paragraphs) {
				String remaining = paragraph.trim();
				if (remaining.isEmpty()) {
					wrapped.add("");
					continue;
				}
				while (remaining.length() > MAX_LINE_LENGTH) {
					int split = remaining.lastIndexOf(' ', MAX_LINE_LENGTH);
					if (split <= 0) split = MAX_LINE_LENGTH;
					wrapped.add(remaining.substring(0, split).trim());
					remaining = remaining.substring(split).trim();
				}
				wrapped.add(remaining);
			}
		}
		return wrapped;
	}

	private String escape(String value) {
		String safe = value == null ? "" : value;
		return safe.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
				.replaceAll("[^\\x20-\\xFF]", "?");
	}

	private byte[] bytes(String value) {
		return value.getBytes(PDF_CHARSET);
	}

	private void write(ByteArrayOutputStream output, String value) {
		output.writeBytes(bytes(value));
	}
}
