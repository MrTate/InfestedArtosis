package learning;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@Jacksonized
@Builder
@Data
public class OpenerRecord {
    private String opener;
    private int wins;
    private int losses;

    public int netWins() {
        return wins - losses;
    }
}
