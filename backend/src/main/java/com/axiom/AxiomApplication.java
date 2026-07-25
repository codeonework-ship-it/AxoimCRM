package com.axiom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AxiomApplication {
    public static void main(String[] args) {
        SpringApplication.run(AxiomApplication.class, args);
    }
}
