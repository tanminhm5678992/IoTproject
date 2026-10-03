import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Công cụ dòng lệnh: sinh hash BCrypt cho mật khẩu lấy từ đối số.
 *
 * Chạy KHÔNG cần build Maven (java single-file source, JDK 11+):
 *   java -cp <spring-security-crypto.jar>;<spring-jcl.jar> scripts/tools/BcryptHash.java <mat-khau>
 *
 * BẮT BUỘC có spring-jcl trên classpath: BCryptPasswordEncoder dùng
 * org.apache.commons.logging.LogFactory do spring-jcl cung cấp; thiếu nó sẽ lỗi
 * "NoClassDefFoundError: org/apache/commons/logging/LogFactory".
 *
 * Được scripts/demo-reset.sh dùng để seed lại tài khoản admin từ .env (script này tự
 * tìm jar trong ~/.m2 và tự đổi đường dẫn sang dạng Windows khi chạy trên Git Bash).
 * Không khai báo package để chạy được ở chế độ single-file source.
 */
public class BcryptHash {

    public static void main(String[] args) {
        if (args.length != 1 || args[0].isEmpty()) {
            System.err.println("Cách dùng: java BcryptHash.java <mat-khau>");
            System.exit(2);
        }
        System.out.println(new BCryptPasswordEncoder().encode(args[0]));
    }
}
