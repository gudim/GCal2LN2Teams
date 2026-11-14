import pytest
from click.testing import CliRunner
from src.main import cli

def test_cli_help():
    """Test that the CLI help command runs successfully."""
    runner = CliRunner()
    result = runner.invoke(cli, ['--help'])
    assert result.exit_code == 0
    assert 'Google Calendar to Lotus Notes Sync - CLI' in result.output

def test_cli_version():
    """Test that the CLI version command runs successfully."""
    runner = CliRunner()
    result = runner.invoke(cli, ['--version'])
    assert result.exit_code == 0
    assert 'version 1.0.0' in result.output
