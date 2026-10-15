using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Mes.Api.Data.Migrations
{
    /// <inheritdoc />
    public partial class TestSopSnapshot : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<Guid>(
                name: "SopVersionId",
                table: "TestTask",
                type: "uuid",
                nullable: true);

            migrationBuilder.CreateIndex(
                name: "IX_TestTask_SopVersionId",
                table: "TestTask",
                column: "SopVersionId");

            migrationBuilder.AddForeignKey(
                name: "FK_TestTask_SopVersion_SopVersionId",
                table: "TestTask",
                column: "SopVersionId",
                principalTable: "SopVersion",
                principalColumn: "Id",
                onDelete: ReferentialAction.Restrict);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropForeignKey(
                name: "FK_TestTask_SopVersion_SopVersionId",
                table: "TestTask");

            migrationBuilder.DropIndex(
                name: "IX_TestTask_SopVersionId",
                table: "TestTask");

            migrationBuilder.DropColumn(
                name: "SopVersionId",
                table: "TestTask");
        }
    }
}
