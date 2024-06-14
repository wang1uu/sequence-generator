package cc.wang1;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class TestMain {
    public static void main(String[] args) {
        SequenceGenerator sequenceGenerator = new SequenceGenerator();

        ExecutorService executorService = Executors.newFixedThreadPool(100);

        for (int i = 0; i < 100; i++) {
            executorService.submit(() -> {
                for (int j = 0; j < 100; j++) {
                    System.out.println(sequenceGenerator.generateSequence());
                }
            });
        }
    }
}
