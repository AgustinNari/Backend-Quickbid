package com.example.quickbid.quickbid;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "app.mail.enabled=false")
@ActiveProfiles("test")
class QuickbidApplicationTests {

	@Test
	void contextLoads() {
	}

}
