import json
import argparse

# Command Line Arguments
parser = argparse.ArgumentParser(description="Extract coverage rates from JSON.")
parser.add_argument("file_path", help="Path to the coverage-summary.json file")
parser.add_argument("--debug", action="store_true", help="Enable debug mode")


"""
    Function Name: extract_coverage_rates
    Description  : Extract code covearge result from coverage-summary.json

    Args:
        file_path (str) : The file path of 'coverage-summary.json'.  
        debug (bool)    : Enable or disable debug display.

    Returns:
        dict: Response status and message from Bitbucket (success or failed).
"""
def extract_coverage_rates(file_path, debug=False):
    """
    Extracts coverage rates from a JSON file and returns them as a dictionary.

    Args:
        file_path (str): Path to the coverage-summary.json file.
        debug (bool): Flag to enable debug output.

    Returns:
        dict: 4 different code coverage rates(lines, statements, functions, branches). 
    """
    # Load the JSON file
    with open(file_path, 'r') as file:
        data = json.load(file)

    # Extract coverage percentages
    coverage_rates = {
        'lines': data['total']['lines']['pct'],
        'statements': data['total']['statements']['pct'],
        'functions': data['total']['functions']['pct'],
        'branches': data['total']['branches']['pct']
    }

    # Debug output
    if debug:
        print("Coverage debug Info:")
        print(json.dumps(coverage_rates, indent=4))

    return coverage_rates


# Main function
def main(file_path, debug=False):
    """
    Main function to return and print coverage data.

    Args:
        file_path (str): Path to the coverage-summary.json file.
        debug (bool): Flag to enable debug output.

    Returns:
        dict: Coverage percentage data.
    """
    result = extract_coverage_rates(file_path, debug)

    if debug:
        for key, value in result.items():
            print(f"{key.capitalize()} Coverage: {value}%")  # Print the output for debugging

    return result  # Return dictonary type of result for external usage 

# Execution entry point
if __name__ == "__main__":
    args = parser.parse_args()  # Parse command-line arguments
    main(args.file_path, args.debug)