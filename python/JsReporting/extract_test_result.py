import json
import pandas as pd
import sys
import os
import argparse
import re


# Command Line Arguments
parser = argparse.ArgumentParser(description="Extract test results from JSON.")
parser.add_argument("file_path", help="Path to the test-results.json file")
parser.add_argument("project_folder_name", help="server, client or client-portual?")
parser.add_argument("--debug", action="store_true", help="Enable debug mode")
parser.add_argument("--passed", action="store_true", help="Show only passed tests")
parser.add_argument("--failed", action="store_true", help="Show only failed tests")

# Set display options to show all rows and columns
pd.set_option('display.max_rows', None)
pd.set_option('display.max_columns', None)
pd.set_option('display.width', None)
pd.set_option('display.max_colwidth', None)


"""
    Function Name: extract_test_summary
    Description  : Extracts a summary of test results from a Jest/Vitest test-results.json file.

    Args:
        file_path (str)    : Path to the test-results.json file.
        debug (bool)       : Flag to enable debug output.
        filter_status(str) : Filter the test results based on status ("passed", "failed", etc.).

    Returns:
        dict: A dictionary containing the summary and details of the test results.
"""
def extract_test_summary(file_path, debug=False, filter_status=None):

    # Check if the file exists
    if not os.path.isfile(file_path):
        print(f"Error: The file '{file_path}' does not exist.")
        sys.exit(1)

    # Read JSON file with UTF-8 encoding
    with open(file_path, 'r', encoding='utf-8') as file:
        data = json.load(file)

    # Extract summary information
    summary = {
        "total_tests": data.get("numTotalTests", 0),
        "passed_tests": data.get("numPassedTests", 0),
        "failed_tests": data.get("numFailedTests", 0),
        "todo_tests": data.get("numTodoTests", 0),
        "tests": []
    }

    # Extract individual test results
    # Supports both "assertionResults" and "testResults" formats for compatibility
    for test_result in data.get("testResults", []):
        # Retrieve test results from either "assertionResults" or "testResults"
        test_cases = test_result.get("assertionResults") or test_result.get("testResults") or []
        
        for assertion in test_cases:
            summary["tests"].append({
                "test_name": assertion.get("title", "Unknown Test"),
                "status": assertion.get("status", "unknown")
            })

    # Convert to DataFrame
    df = pd.DataFrame(summary["tests"])

    # Apply filter if specified
    if filter_status in ["passed", "failed", "todo"]:
        df = df[df["status"] == filter_status]

    # Build the return value
    result = {
        "summary": {
            "total_tests": summary["total_tests"],
            "passed_tests": summary["passed_tests"],
            "failed_tests": summary["failed_tests"],
            "todo_tests": summary["todo_tests"]
        },
        "details": df.to_dict(orient='records') 
    }

    # Debug output with filter support
    if debug:
        print("Test debug Info:")
        print("\n Summary:")
        print(f"Total Tests : {result['summary']['total_tests']}")
        print(f"Passed Tests: {result['summary']['passed_tests']}")
        print(f"Failed Tests: {result['summary']['failed_tests']}")
        print(f"Todo Tests  : {result['summary']['todo_tests']}")

        print("\n Detailed Results:")
        for test in result["details"]:
            print(f" - Test Name: {test['test_name']}, Status: {test['status']}")

    # Return summary information with detailed results
    return result


"""
    Helper functions for "extract_failure_details"
"""
def remove_ansi_codes(text):
    ansi_escape = re.compile(r'\x1b\[[0-9;]*m')
    return ansi_escape.sub('', text)

def clean_html(raw_text):
    clean_regex = re.compile('<.*?>')
    return re.sub(clean_regex, '', raw_text)

def clean_escape_characters(text):
    return text.replace('\\', '/').replace('"', '"')

def sanitize_description(description, max_length=2000):
    """
    Removes problematic characters and limits the description length.
    """
    description = clean_escape_characters(clean_html(remove_ansi_codes(description)))
    description = description.replace('\n', '').replace('\\', '/')  # Remove newline characters
    return description[:max_length]  # Limit the length to prevent API issues

def remove_node_module_lines(message):
    # Remove lines containing 'node_module' or 'node_modules'
    return '\n'.join([
        line for line in message.split('\n') 
        if 'node_module' not in line and 'node_modules' not in line
    ])


"""
    Function Name: extract_failure_details
    Description  : Extract test failure error messages and their corresponding test names.

    Args:
        file_path (str)    : Path to the test-results.json file.
        project_type (bool): The project type (e.g., "server" or "client").
        debug (bool)       : Flag to enable debug output.

    Returns:
        list: A list of dictionaries containing failure details.
"""
def extract_failure_details(file_path, project_type, debug=False):
    # Extract the list of failed tests ("failed") from the given test-results.json file
    test_summary = extract_test_summary(file_path, debug, filter_status="failed")

    # Read failed test information from the JSON file
    failed_tests_info = []
    with open(file_path, 'r', encoding='utf-8') as file:
        data = json.load(file)

    # Retrieve failed test names from test_summary['details']
    for failed_test in test_summary['details']:
        test_name = failed_test['test_name']

        # Retrieve details of failed tests from test-results.json
        for test_result in data.get("testResults", []):
            source_file = test_result.get("name", "Unknown File")

            # Retrieve test case results (supports both assertionResults and testResults)
            test_cases = test_result.get("assertionResults") or test_result.get("testResults") or []
            
            for assertion in test_cases:
                # Process only failed tests by checking if they match failed 'test_name' 
                if assertion.get("title") == test_name:
                    # Retrieve the value of assertion["failureMessages"]
                    failure_messages = assertion.get("failureMessages", []) or assertion.get("failureDetails", [])

                    # Remove unnecessary values from description data
                    for message in failure_messages:
                        message = remove_node_module_lines(message)                             # Remove lines containing 'node_module' or 'node_modules'
                        formatted_message = sanitize_description(message)                       # Remove unnecessary spaces, special characters, etc.

                        # Extract file name and line number
                        match = re.search(r'at (.+):(\d+):(\d+)', message)  # Match file path and line number

                        # Extract file name and line number if regular expression match result is found
                        line_number = "N/A"
                        if match:
                            source_file = match.group(1)  # Absolute source file path
                            line_number = int(match.group(2))  # Error occurred line number

                        # Change absolute path to relative path so that Bitbucket properly points to the source code
                        if project_type in source_file:  # Check if the source_file path contains the project_type (e.g., client or server)
                            source_file = source_file.split(project_type, 1)[-1]  # Get only the part after project_type
                            source_file = os.path.join(project_type, source_file.lstrip('\\/'))  # Remove / or \(backslash) from the front of the path
                            source_file = source_file.replace('\\', '/')  # Replace backslashes with forward slashes

                        # Store data
                        failed_tests_info.append({
                            "test_name": test_name,
                            "source_file": source_file,
                            "line_number": line_number,
                            "description": formatted_message
                        })

    if debug:
        print("\nProcessed Failed Test Details:")
        for item in failed_tests_info:
            print(json.dumps(item, indent=4))

    return failed_tests_info


# Function for disply API response from Bitbucket
def handle_annotation_response(annotation_api_result, debug=False):
    if debug and annotation_api_result.get("status") == "failed":
        print("\n\nBitbucket Annotation API Request Response:")
        print("Status: FAILED")
        print(json.dumps(annotation_api_result.get("error", {}), indent=4))
   

def main(file_path, project_folder_name, debug=False, filter_status=None):
    # Extract test results
    extract_test_summary(file_path, debug, filter_status)
    extract_failure_details(file_path, project_folder_name, debug)


if __name__ == "__main__":
    # Parse arguments
    args = parser.parse_args()

    # Determine filter status
    filter_status = None
    if args.passed:
        filter_status = "passed"
    elif args.failed:
        filter_status = "failed"

    # Run the main function
    result = main(args.file_path, args.project_folder_name, args.debug, filter_status)

    # Only print if --debug is provided
    if args.debug:
        print(json.dumps(result, indent=4))
