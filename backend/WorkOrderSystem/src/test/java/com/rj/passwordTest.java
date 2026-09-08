package com.rj;

import com.rj.util.PasswordUtil;
import org.junit.jupiter.api.Test;

public class passwordTest {
    @Test
    void createToken() {
        PasswordUtil ps = new PasswordUtil(10);

        System.out.println("admin123加密后：");
        System.out.println(ps.encode("admin123"));
    }
}
