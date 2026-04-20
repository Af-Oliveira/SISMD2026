import java.io.IOException;
import java.util.Scanner;

public class ApplyFilters {
 
    public static void main(String[] args) throws IOException {
		
		Scanner input = new Scanner(System.in);        
		String filePath = "";
		System.out.println("Insert the name of the file path you would like to use.");
        filePath = input.nextLine();
        input.close();
        Filters filters = new Filters(filePath);
        filters.HistogramFilter("output.jpg",128);
    }

} 
